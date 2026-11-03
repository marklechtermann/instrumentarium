package de.instrumentarium.core.source

import de.instrumentarium.core.model.VariableInfo
import de.instrumentarium.core.model.VariableKind
import de.instrumentarium.core.xcp.A2lDataType
import de.instrumentarium.core.xcp.A2lFile
import de.instrumentarium.core.xcp.A2lObject
import de.instrumentarium.core.xcp.XcpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToLong

/** XCP on Ethernet device described by an A2L file. Measures by polling, calibrates by DOWNLOAD. */
class XcpDataSource(
    private val host: String,
    private val port: Int,
    private val udp: Boolean,
    a2l: A2lFile,
) : DataSource {
    override val displayName = "XCP on ${if (udp) "UDP" else "TCP"} $host:$port (${a2l.projectName})"
    private val objects: Map<String, A2lObject> = a2l.objects.associateBy { it.name }
    override val variables: List<VariableInfo> = a2l.objects.map { it.toVariableInfo() }

    private val client = XcpClient(host, port, udp)

    override suspend fun connect() = withContext(Dispatchers.IO) { client.connect() }

    override suspend fun disconnect() = withContext(Dispatchers.IO) { client.disconnect() }

    override suspend fun read(names: Collection<String>): Map<String, Double> = withContext(Dispatchers.IO) {
        val out = HashMap<String, Double>(names.size)
        for (n in names) {
            val o = objects[n] ?: continue
            val raw = client.upload(o.address, o.addressExtension, o.dataType.size)
            out[n] = o.conversion.toPhys(decode(raw, o.dataType, client.byteOrder))
        }
        out
    }

    override suspend fun write(name: String, value: Double) = withContext(Dispatchers.IO) {
        val o = objects[name] ?: error("$name not found in A2L")
        require(o.kind == VariableKind.PARAMETER) { "$name is not a parameter" }
        val phys = value.coerceIn(o.lower, o.upper)
        client.download(o.address, o.addressExtension, encode(o.conversion.toRaw(phys), o.dataType, client.byteOrder))
    }

    companion object {
        fun decode(b: ByteArray, t: A2lDataType, order: ByteOrder): Double {
            val bb = ByteBuffer.wrap(b).order(order)
            return when (t) {
                A2lDataType.UBYTE -> (b[0].toInt() and 0xFF).toDouble()
                A2lDataType.SBYTE -> b[0].toDouble()
                A2lDataType.UWORD -> (bb.short.toInt() and 0xFFFF).toDouble()
                A2lDataType.SWORD -> bb.short.toDouble()
                A2lDataType.ULONG -> (bb.int.toLong() and 0xFFFFFFFFL).toDouble()
                A2lDataType.SLONG -> bb.int.toDouble()
                A2lDataType.A_UINT64 -> bb.long.toULong().toDouble()
                A2lDataType.A_INT64 -> bb.long.toDouble()
                A2lDataType.FLOAT32_IEEE -> bb.float.toDouble()
                A2lDataType.FLOAT64_IEEE -> bb.double
            }
        }

        fun encode(raw: Double, t: A2lDataType, order: ByteOrder): ByteArray {
            val bb = ByteBuffer.allocate(t.size).order(order)
            val r = raw.roundToLong()
            when (t) {
                A2lDataType.UBYTE -> bb.put(r.coerceIn(0, 255).toByte())
                A2lDataType.SBYTE -> bb.put(r.coerceIn(-128, 127).toByte())
                A2lDataType.UWORD -> bb.putShort(r.coerceIn(0, 65535).toShort())
                A2lDataType.SWORD -> bb.putShort(r.coerceIn(-32768, 32767).toShort())
                A2lDataType.ULONG -> bb.putInt(r.coerceIn(0, 0xFFFFFFFFL).toInt())
                A2lDataType.SLONG -> bb.putInt(r.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt())
                A2lDataType.A_UINT64, A2lDataType.A_INT64 -> bb.putLong(r)
                A2lDataType.FLOAT32_IEEE -> bb.putFloat(raw.toFloat())
                A2lDataType.FLOAT64_IEEE -> bb.putDouble(raw)
            }
            return bb.array()
        }
    }
}
