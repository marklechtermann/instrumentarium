package de.instrumentarium.core.export

import de.instrumentarium.core.measure.Recording
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

object CsvWriter {
    fun write(rec: Recording): String {
        val sb = StringBuilder()
        sb.append("time [s]")
        rec.variables.forEach { sb.append(',').append(it.name).append(" [").append(it.unit).append(']') }
        sb.append('\n')
        for (i in 0 until rec.samples) {
            sb.append(String.format(Locale.US, "%.6f", rec.times[i]))
            rec.columns.forEach { sb.append(',').append(String.format(Locale.US, "%.6g", it[i])) }
            sb.append('\n')
        }
        return sb.toString()
    }
}

/**
 * Writes a recording as ASAM MDF 4.10 (one data group, one channel group, float64 channels,
 * unsorted-free "sorted" layout). Readable by asammdf, CANape and other MDF4 tools.
 */
object Mdf4Writer {

    private class Block(val id: String, val linkCount: Int, val data: ByteArray) {
        val links = arrayOfNulls<Block>(linkCount)
        var offset = 0L
        val length get() = 24L + 8L * linkCount + data.size
    }

    private fun le(size: Int) = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)

    private fun text(id: String, s: String): Block {
        val bytes = s.toByteArray(Charsets.UTF_8)
        val padded = ByteArray(((bytes.size + 1 + 7) / 8) * 8)
        System.arraycopy(bytes, 0, padded, 0, bytes.size)
        return Block(id, 0, padded)
    }

    fun write(rec: Recording, tool: String = "Instrumentarium"): ByteArray {
        val blocks = ArrayList<Block>()
        fun <T : Block> add(b: T): T { blocks.add(b); return b }

        val startNs = rec.startedAtMillis * 1_000_000L
        val hd = add(Block("##HD", 6, le(32).putLong(startNs).putShort(0).putShort(0).put(0).put(0).put(0).put(0).putDouble(0.0).putDouble(0.0).array()))
        val fh = add(Block("##FH", 2, le(16).putLong(startNs).putShort(0).putShort(0).put(0).put(0).put(0).put(0).array()))
        fh.links[1] = add(text("##MD", "<FHcomment><TX>Recorded with $tool</TX><tool_id>$tool</tool_id><tool_vendor>-</tool_vendor><tool_version>1.0</tool_version></FHcomment>"))
        hd.links[1] = fh

        val nCh = rec.variables.size + 1
        val recordBytes = 8 * nCh
        val dg = add(Block("##DG", 4, le(8).put(0).put(ByteArray(7)).array()))
        hd.links[0] = dg
        val cgData = le(32).putLong(0).putLong(rec.samples.toLong()).putShort(0).putShort(0).putInt(0).putInt(recordBytes).putInt(0).array()
        val cg = add(Block("##CG", 6, cgData))
        dg.links[1] = cg
        cg.links[2] = add(text("##TX", rec.name))

        fun channel(name: String, unit: String, master: Boolean, byteOffset: Int, min: Double, max: Double): Block {
            val d = le(72)
                .put(if (master) 2 else 0) // cn_type: master / fixed length
                .put(if (master) 1 else 0) // cn_sync_type: time
                .put(4) // IEEE 754 little endian
                .put(0).putInt(byteOffset).putInt(64)
                .putInt(if (master) 0 else 0x18) // value range + limit valid
                .putInt(0).put(6).put(0).putShort(0)
                .putDouble(min).putDouble(max).putDouble(min).putDouble(max).putDouble(0.0).putDouble(0.0)
                .array()
            val cn = add(Block("##CN", 8, d))
            cn.links[2] = add(text("##TX", name))
            if (unit.isNotEmpty()) cn.links[6] = add(text("##TX", unit))
            return cn
        }

        val time = channel("time", "s", true, 0, 0.0, rec.duration)
        cg.links[1] = time
        var prev = time
        rec.variables.forEachIndexed { i, v ->
            val cn = channel(v.name, v.unit, false, 8 * (i + 1), v.min, v.max)
            if (v.description.isNotEmpty()) cn.links[7] = add(text("##TX", v.description))
            prev.links[0] = cn
            prev = cn
        }

        val payload = le(recordBytes * rec.samples)
        for (s in 0 until rec.samples) {
            payload.putDouble(rec.times[s])
            rec.columns.forEach { payload.putDouble(it[s]) }
        }
        val dt = add(Block("##DT", 0, payload.array()))
        dg.links[2] = dt

        // Lay out blocks after the 64-byte ID block, 8-byte aligned.
        var pos = 64L
        for (b in blocks) {
            b.offset = pos
            pos += (b.length + 7) / 8 * 8
        }
        val out = ByteArrayOutputStream(pos.toInt())
        val id = le(64)
        id.put("MDF     ".toByteArray()).put("4.10    ".toByteArray()).put("CDMobile".toByteArray())
        id.put(ByteArray(4)).putShort(410).put(ByteArray(30)).putShort(0).putShort(0)
        out.write(id.array())
        for (b in blocks) {
            val h = le(24 + 8 * b.linkCount)
            h.put(b.id.toByteArray()).putInt(0).putLong(b.length).putLong(b.linkCount.toLong())
            b.links.forEach { h.putLong(it?.offset ?: 0L) }
            out.write(h.array())
            out.write(b.data)
            val pad = ((b.length + 7) / 8 * 8 - b.length).toInt()
            if (pad > 0) out.write(ByteArray(pad))
        }
        return out.toByteArray()
    }
}
