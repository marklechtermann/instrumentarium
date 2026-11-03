package de.instrumentarium.core.xcp

import java.io.DataInputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder

class XcpException(message: String) : IOException(message)

/**
 * Minimal XCP master for XCP on Ethernet (ASAM MCD-1 XCP 1.x), TCP or UDP.
 * Supports CONNECT, DISCONNECT, GET_STATUS, SHORT_UPLOAD, SET_MTA, DOWNLOAD (polling + calibration).
 * Blocking API; callers run it on an IO thread.
 */
class XcpClient(
    private val host: String,
    private val port: Int,
    private val udp: Boolean,
    private val timeoutMs: Int = 1000,
) {
    companion object {
        const val CMD_CONNECT = 0xFF
        const val CMD_DISCONNECT = 0xFE
        const val CMD_GET_STATUS = 0xFD
        const val CMD_SET_MTA = 0xF6
        const val CMD_SHORT_UPLOAD = 0xF4
        const val CMD_DOWNLOAD = 0xF0

        val ERRORS = mapOf(
            0x00 to "ERR_CMD_SYNCH", 0x10 to "ERR_CMD_BUSY", 0x11 to "ERR_DAQ_ACTIVE", 0x12 to "ERR_PGM_ACTIVE",
            0x20 to "ERR_CMD_UNKNOWN", 0x21 to "ERR_CMD_SYNTAX", 0x22 to "ERR_OUT_OF_RANGE", 0x23 to "ERR_WRITE_PROTECTED",
            0x24 to "ERR_ACCESS_DENIED", 0x25 to "ERR_ACCESS_LOCKED", 0x26 to "ERR_PAGE_NOT_VALID", 0x27 to "ERR_MODE_NOT_VALID",
            0x28 to "ERR_SEGMENT_NOT_VALID", 0x29 to "ERR_SEQUENCE", 0x2A to "ERR_DAQ_CONFIG", 0x30 to "ERR_MEMORY_OVERFLOW",
            0x31 to "ERR_GENERIC", 0x32 to "ERR_VERIFY",
        )
    }

    private var tcp: Socket? = null
    private var tcpIn: DataInputStream? = null
    private var dgram: DatagramSocket? = null
    private var ctr = 0

    var maxCto = 8; private set
    var maxDto = 8; private set
    /** Byte order of the slave's data (COMM_MODE_BASIC bit 0: 1 = Motorola). */
    var byteOrder: ByteOrder = ByteOrder.LITTLE_ENDIAN; private set
    var protocolVersion = 1; private set
    val connected get() = tcp != null || dgram != null

    fun connect() {
        if (udp) {
            val target = InetSocketAddress(InetAddress.getByName(host), port)
            dgram = DatagramSocket().apply {
                soTimeout = timeoutMs
                connect(target)
            }
        } else {
            val s = Socket()
            s.connect(InetSocketAddress(host, port), timeoutMs * 3)
            s.soTimeout = timeoutMs
            s.tcpNoDelay = true
            tcp = s
            tcpIn = DataInputStream(s.getInputStream().buffered())
        }
        try {
            val r = command(byteArrayOf(CMD_CONNECT.toByte(), 0))
            val commModeBasic = r[2].toInt() and 0xFF
            byteOrder = if (commModeBasic and 1 == 1) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN
            maxCto = r[3].toInt() and 0xFF
            maxDto = ByteBuffer.wrap(r, 4, 2).order(byteOrder).short.toInt() and 0xFFFF
            protocolVersion = r[6].toInt() and 0xFF
        } catch (e: Exception) {
            close()
            throw e
        }
    }

    fun disconnect() {
        try {
            if (connected) command(byteArrayOf(CMD_DISCONNECT.toByte()))
        } catch (_: Exception) {
        } finally {
            close()
        }
    }

    private fun close() {
        try { tcp?.close() } catch (_: Exception) {}
        try { dgram?.close() } catch (_: Exception) {}
        tcp = null; tcpIn = null; dgram = null
    }

    fun getStatus(): ByteArray = command(byteArrayOf(CMD_GET_STATUS.toByte()))

    private fun addr(buf: ByteBuffer, address: Long) {
        buf.order(byteOrder).putInt(address.toInt())
    }

    /** Reads [size] bytes from [address]. Splits into several SHORT_UPLOADs if needed. */
    fun upload(address: Long, ext: Int, size: Int): ByteArray {
        val out = ByteArray(size)
        var done = 0
        while (done < size) {
            val n = minOf(size - done, maxCto - 1)
            val b = ByteBuffer.allocate(8)
            b.put(CMD_SHORT_UPLOAD.toByte()).put(n.toByte()).put(0).put(ext.toByte())
            addr(b, address + done)
            val r = command(b.array())
            if (r.size < 1 + n) throw XcpException("SHORT_UPLOAD: response too short (${r.size} bytes)")
            System.arraycopy(r, 1, out, done, n)
            done += n
        }
        return out
    }

    fun setMta(address: Long, ext: Int) {
        val b = ByteBuffer.allocate(8)
        b.put(CMD_SET_MTA.toByte()).put(0).put(0).put(ext.toByte())
        addr(b, address)
        command(b.array())
    }

    fun download(address: Long, ext: Int, data: ByteArray) {
        setMta(address, ext)
        var done = 0
        while (done < data.size) {
            val n = minOf(data.size - done, maxCto - 2)
            val cmd = ByteArray(2 + n)
            cmd[0] = CMD_DOWNLOAD.toByte(); cmd[1] = n.toByte()
            System.arraycopy(data, done, cmd, 2, n)
            command(cmd)
            done += n
        }
    }

    /** Sends one CTO and returns the positive response (starting with 0xFF). */
    @Synchronized
    fun command(cto: ByteArray): ByteArray {
        val frame = ByteBuffer.allocate(4 + cto.size).order(ByteOrder.LITTLE_ENDIAN)
        frame.putShort(cto.size.toShort()).putShort((ctr++ and 0xFFFF).toShort()).put(cto)
        val bytes = frame.array()
        val deadline = System.currentTimeMillis() + timeoutMs
        if (udp) {
            val s = dgram ?: throw XcpException("Not connected")
            s.send(DatagramPacket(bytes, bytes.size))
            val buf = ByteArray(2048)
            while (true) {
                if (System.currentTimeMillis() > deadline) throw XcpException("Timeout")
                val p = DatagramPacket(buf, buf.size)
                try { s.receive(p) } catch (e: SocketTimeoutException) { throw XcpException("Timeout") }
                // A datagram can carry several XCP packets; take the first response packet.
                val bb = ByteBuffer.wrap(buf, 0, p.length).order(ByteOrder.LITTLE_ENDIAN)
                while (bb.remaining() >= 4) {
                    val len = bb.short.toInt() and 0xFFFF
                    bb.short
                    if (len > bb.remaining()) break
                    val pkt = ByteArray(len).also { bb.get(it) }
                    checkResponse(pkt)?.let { return it }
                }
            }
        } else {
            val s = tcp ?: throw XcpException("Not connected")
            s.getOutputStream().apply { write(bytes); flush() }
            val input = tcpIn!!
            while (true) {
                if (System.currentTimeMillis() > deadline) throw XcpException("Timeout")
                val hdr = ByteArray(4)
                try { input.readFully(hdr) } catch (e: SocketTimeoutException) { throw XcpException("Timeout") }
                val len = (hdr[0].toInt() and 0xFF) or ((hdr[1].toInt() and 0xFF) shl 8)
                val pkt = ByteArray(len).also { input.readFully(it) }
                checkResponse(pkt)?.let { return it }
            }
        }
    }

    /** Returns the packet if it is a positive response, throws on error, null for events/service packets. */
    private fun checkResponse(pkt: ByteArray): ByteArray? {
        if (pkt.isEmpty()) return null
        return when (pkt[0].toInt() and 0xFF) {
            0xFF -> pkt
            0xFE -> {
                val code = if (pkt.size > 1) pkt[1].toInt() and 0xFF else -1
                throw XcpException("XCP error ${ERRORS[code] ?: "0x%02X".format(code)}")
            }
            else -> null // EV (0xFD), SERV (0xFC) or DAQ packets
        }
    }
}
