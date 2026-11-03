package de.instrumentarium.core.xcp

import de.instrumentarium.core.model.VariableInfo
import de.instrumentarium.core.model.VariableKind

enum class A2lDataType(val size: Int, val signed: Boolean, val float: Boolean) {
    UBYTE(1, false, false), SBYTE(1, true, false),
    UWORD(2, false, false), SWORD(2, true, false),
    ULONG(4, false, false), SLONG(4, true, false),
    A_UINT64(8, false, false), A_INT64(8, true, false),
    FLOAT32_IEEE(4, true, true), FLOAT64_IEEE(8, true, true);

    companion object {
        fun of(s: String) = entries.firstOrNull { it.name == s }
    }
}

/** ASAM MCD-2 MC conversion from internal (raw) to physical values. */
sealed class Conversion {
    abstract fun toPhys(raw: Double): Double
    abstract fun toRaw(phys: Double): Double

    data object Identical : Conversion() {
        override fun toPhys(raw: Double) = raw
        override fun toRaw(phys: Double) = phys
    }

    /** phys = a * raw + b */
    data class Linear(val a: Double, val b: Double) : Conversion() {
        override fun toPhys(raw: Double) = a * raw + b
        override fun toRaw(phys: Double) = (phys - b) / a
    }

    /** raw = (a*p^2 + b*p + c) / (d*p^2 + e*p + f); inverse supported for a = d = 0. */
    data class RatFunc(val a: Double, val b: Double, val c: Double, val d: Double, val e: Double, val f: Double) : Conversion() {
        override fun toRaw(phys: Double) = (a * phys * phys + b * phys + c) / (d * phys * phys + e * phys + f)
        override fun toPhys(raw: Double): Double {
            require(a == 0.0 && d == 0.0) { "RAT_FUNC with quadratic terms is not supported" }
            return (c - raw * f) / (raw * e - b)
        }
    }
}

data class A2lObject(
    val name: String,
    val description: String,
    val kind: VariableKind,
    val dataType: A2lDataType,
    val address: Long,
    val addressExtension: Int,
    val conversion: Conversion,
    val lower: Double,
    val upper: Double,
    val unit: String,
    val valueTable: Map<Int, String>,
) {
    fun toVariableInfo() = VariableInfo(name, description, unit, kind, lower, upper, valueTable)
}

data class A2lFile(val projectName: String, val objects: List<A2lObject>, val warnings: List<String>)

/**
 * Pragmatic A2L reader: MEASUREMENT and scalar CHARACTERISTIC (VALUE) objects with
 * COMPU_METHOD (IDENTICAL, LINEAR, RAT_FUNC, TAB_VERB), RECORD_LAYOUT and COMPU_VTAB.
 */
object A2lParser {

    fun tokenize(text: String): List<String> {
        val out = ArrayList<String>()
        var i = 0
        val n = text.length
        while (i < n) {
            val c = text[i]
            when {
                c.isWhitespace() -> i++
                c == '/' && i + 1 < n && text[i + 1] == '*' -> {
                    val end = text.indexOf("*/", i + 2)
                    i = if (end < 0) n else end + 2
                }
                c == '/' && i + 1 < n && text[i + 1] == '/' -> {
                    val end = text.indexOf('\n', i)
                    i = if (end < 0) n else end + 1
                }
                c == '"' -> {
                    val sb = StringBuilder()
                    i++
                    while (i < n) {
                        val ch = text[i]
                        if (ch == '\\' && i + 1 < n) { sb.append(text[i + 1]); i += 2; continue }
                        if (ch == '"') {
                            if (i + 1 < n && text[i + 1] == '"') { sb.append('"'); i += 2; continue }
                            i++; break
                        }
                        sb.append(ch); i++
                    }
                    out.add("\"" + sb.toString())
                }
                else -> {
                    val start = i
                    while (i < n && !text[i].isWhitespace() && text[i] != '"') i++
                    out.add(text.substring(start, i))
                }
            }
        }
        return out
    }

    private fun str(t: String) = if (t.startsWith("\"")) t.substring(1) else t

    fun num(t: String): Double {
        val s = t.trim()
        return if (s.startsWith("0x") || s.startsWith("0X")) s.substring(2).toLong(16).toDouble() else s.toDouble()
    }

    private class Block(val type: String, val tokens: List<String>)

    /** Finds all blocks of the wanted types at any nesting depth; each body keeps its nested blocks. */
    private fun blocks(tokens: List<String>, wanted: Set<String>): List<Block> {
        val result = ArrayList<Block>()
        for (i in tokens.indices) {
            if (tokens[i] != "/begin" || i + 1 >= tokens.size || tokens[i + 1] !in wanted) continue
            var depth = 1
            var j = i + 2
            val body = ArrayList<String>()
            while (j < tokens.size) {
                if (tokens[j] == "/begin") depth++
                if (tokens[j] == "/end" && --depth == 0) break
                body.add(tokens[j]); j++
            }
            result.add(Block(tokens[i + 1], body))
        }
        return result
    }

    /** Value following keyword [kw] at nesting depth 0 of this block, if present. */
    private fun keyword(body: List<String>, kw: String, from: Int): String? {
        var depth = 0
        for (k in from until body.size) {
            val t = body[k]
            if (t == "/begin") depth++ else if (t == "/end") depth--
            else if (depth == 0 && t == kw && k + 1 < body.size) return body[k + 1]
        }
        return null
    }

    fun parse(text: String): A2lFile {
        val tokens = tokenize(text)
        val warnings = ArrayList<String>()
        val flat = blocks(tokens, setOf("MEASUREMENT", "CHARACTERISTIC", "COMPU_METHOD", "COMPU_VTAB", "RECORD_LAYOUT"))
        val projectName = tokens.indexOf("PROJECT").let { if (it >= 0 && it + 1 < tokens.size) tokens[it + 1] else "A2L" }

        val vtabs = HashMap<String, Map<Int, String>>()
        flat.filter { it.type == "COMPU_VTAB" }.forEach { b ->
            val t = b.tokens
            val n = num(t[3]).toInt()
            val map = LinkedHashMap<Int, String>()
            for (k in 0 until n) map[num(t[4 + 2 * k]).toInt()] = str(t[5 + 2 * k])
            vtabs[t[0]] = map
        }

        data class Cm(val conv: Conversion, val unit: String, val table: Map<Int, String>)
        val methods = HashMap<String, Cm>()
        flat.filter { it.type == "COMPU_METHOD" }.forEach { b ->
            val t = b.tokens
            val name = t[0]
            val type = t[2]
            val unit = str(t[4])
            val conv = when (type) {
                "LINEAR" -> keywordList(t, "COEFFS_LINEAR", 2)?.let { Conversion.Linear(it[0], it[1]) } ?: Conversion.Identical
                "RAT_FUNC" -> keywordList(t, "COEFFS", 6)?.let { Conversion.RatFunc(it[0], it[1], it[2], it[3], it[4], it[5]) } ?: Conversion.Identical
                "IDENTICAL", "TAB_VERB" -> Conversion.Identical
                else -> { warnings.add("COMPU_METHOD $name: type $type not supported, using IDENTICAL"); Conversion.Identical }
            }
            val table = keyword(t, "COMPU_TAB_REF", 5)?.let { vtabs[it] } ?: emptyMap()
            methods[name] = Cm(conv, unit, table)
        }

        val layouts = HashMap<String, A2lDataType>()
        flat.filter { it.type == "RECORD_LAYOUT" }.forEach { b ->
            keyword(b.tokens, "FNC_VALUES", 1)?.let { _ ->
                val idx = b.tokens.indexOf("FNC_VALUES")
                A2lDataType.of(b.tokens[idx + 2])?.let { layouts[b.tokens[0]] = it }
            }
        }

        val objects = ArrayList<A2lObject>()
        flat.filter { it.type == "MEASUREMENT" }.forEach { b ->
            val t = b.tokens
            try {
                val dt = A2lDataType.of(t[2]) ?: error("unsupported data type ${t[2]}")
                val cm = methods[t[3]]
                val addr = keyword(t, "ECU_ADDRESS", 8) ?: error("ECU_ADDRESS missing")
                objects.add(
                    A2lObject(
                        name = t[0], description = str(t[1]), kind = VariableKind.MEASUREMENT, dataType = dt,
                        address = num(addr).toLong(), addressExtension = keyword(t, "ECU_ADDRESS_EXTENSION", 8)?.let { num(it).toInt() } ?: 0,
                        conversion = cm?.conv ?: Conversion.Identical, lower = num(t[6]), upper = num(t[7]),
                        unit = keyword(t, "PHYS_UNIT", 8)?.let(::str) ?: cm?.unit ?: "", valueTable = cm?.table ?: emptyMap(),
                    )
                )
            } catch (e: Exception) {
                warnings.add("MEASUREMENT ${t.firstOrNull()}: ${e.message}")
            }
        }
        flat.filter { it.type == "CHARACTERISTIC" }.forEach { b ->
            val t = b.tokens
            try {
                if (t[2] != "VALUE") { warnings.add("CHARACTERISTIC ${t[0]}: type ${t[2]} skipped"); return@forEach }
                val dt = layouts[t[4]] ?: error("RECORD_LAYOUT ${t[4]} unknown")
                val cm = methods[t[6]]
                objects.add(
                    A2lObject(
                        name = t[0], description = str(t[1]), kind = VariableKind.PARAMETER, dataType = dt,
                        address = num(t[3]).toLong(), addressExtension = keyword(t, "ECU_ADDRESS_EXTENSION", 9)?.let { num(it).toInt() } ?: 0,
                        conversion = cm?.conv ?: Conversion.Identical, lower = num(t[7]), upper = num(t[8]),
                        unit = keyword(t, "PHYS_UNIT", 9)?.let(::str) ?: cm?.unit ?: "", valueTable = cm?.table ?: emptyMap(),
                    )
                )
            } catch (e: Exception) {
                warnings.add("CHARACTERISTIC ${t.firstOrNull()}: ${e.message}")
            }
        }
        return A2lFile(projectName, objects.sortedBy { it.name }, warnings)
    }

    private fun keywordList(body: List<String>, kw: String, count: Int): List<Double>? {
        val idx = body.indexOf(kw)
        if (idx < 0 || idx + count >= body.size) return null
        return (1..count).map { num(body[idx + it]) }
    }
}
