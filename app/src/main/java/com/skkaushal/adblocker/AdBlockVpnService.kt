package com.skkaushal.adblocker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.Socket
import java.net.InetSocketAddress
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Local DNS-only VPN. It routes only packets addressed to its virtual DNS IP into the TUN.
 * Normal app/web traffic is not routed into the tunnel, avoiding the previous internet black-hole.
 * This is DNS filtering, not HTTPS interception; apps using encrypted DNS may bypass it.
 */
class AdBlockVpnService : VpnService() {
    private var vpnInterface: ParcelFileDescriptor? = null
    private var packetThread: Thread? = null
    private val stopping = AtomicBoolean(false)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopFiltering()
            return START_NOT_STICKY
        }
        if (vpnInterface != null) return START_NOT_STICKY
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        try {
            // Route only the virtual DNS endpoint, NOT 0.0.0.0/0. All other traffic bypasses the VPN.
            vpnInterface = Builder()
                .setSession("SK Ad Blocker DNS")
                .setMtu(1500)
                .addAddress(VPN_ADDRESS, 32)
                .addDnsServer(DNS_ADDRESS)
                .addRoute(DNS_ADDRESS, 32)
                .establish()
            if (vpnInterface == null) throw IllegalStateException("Android did not create the VPN interface")
            running = true
            packetThread = thread(name = "SK-DNS-Filter", isDaemon = true) { packetLoop() }
        } catch (e: Exception) {
            running = false
            vpnInterface?.close()
            vpnInterface = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun packetLoop() {
        val pfd = vpnInterface ?: return
        try {
            FileInputStream(pfd.fileDescriptor).use { input ->
                FileOutputStream(pfd.fileDescriptor).use { output ->
                    val packet = ByteArray(32767)
                    while (!stopping.get()) {
                        val length = input.read(packet)
                        if (length <= 0) break
                        val response = processIpv4UdpDns(packet, length) ?: continue
                        output.write(response)
                    }
                }
            }
        } catch (_: Exception) {
            // Closing the TUN during Stop is expected. Never crash the app for a malformed packet.
        } finally {
            if (!stopping.get()) {
                running = false
                stopSelf()
            }
        }
    }

    private fun processIpv4UdpDns(packet: ByteArray, length: Int): ByteArray? {
        if (length < 28) return null
        val version = (packet[0].toInt() ushr 4) and 0x0f
        if (version != 4) return null
        val ipHeaderLength = (packet[0].toInt() and 0x0f) * 4
        if (ipHeaderLength < 20 || length < ipHeaderLength + 8) return null
        if ((packet[9].toInt() and 0xff) != 17) return null // UDP only
        val destIp = ipv4String(packet, 16)
        if (destIp != DNS_ADDRESS) return null
        val udpOffset = ipHeaderLength
        val srcPort = u16(packet, udpOffset)
        val dstPort = u16(packet, udpOffset + 2)
        if (dstPort != 53) return null
        val udpLength = u16(packet, udpOffset + 4)
        if (udpLength < 8 || udpOffset + udpLength > length) return null
        val dnsOffset = udpOffset + 8
        val dnsLength = udpLength - 8
        if (dnsLength < 12) return null
        val query = packet.copyOfRange(dnsOffset, dnsOffset + dnsLength)
        val domain = readQuestionName(query) ?: return null
        val dnsResponse = if (isBlocked(domain)) makeBlockedResponse(query) else forwardDns(query) ?: makeServFailResponse(query)
        return buildIpv4UdpResponse(packet, ipHeaderLength, srcPort, dnsResponse)
    }

    private fun forwardDns(query: ByteArray): ByteArray? {
        // Try classic DNS first, but do not depend on UDP/53 being allowed by the carrier/Wi-Fi.
        for (resolver in arrayOf("1.1.1.1", "8.8.8.8")) {
            try {
                DatagramSocket().use { socket ->
                    socket.soTimeout = 1800
                    if (!protect(socket)) throw IllegalStateException("Could not bypass local VPN for DNS upstream")
                    val address = InetAddress.getByName(resolver)
                    socket.send(DatagramPacket(query, query.size, address, 53))
                    val responseBuffer = ByteArray(4096)
                    val response = DatagramPacket(responseBuffer, responseBuffer.size)
                    socket.receive(response)
                    if (response.length >= 12) {
                        val bytes = response.data.copyOfRange(0, response.length)
                        // TC=1 means the UDP answer was truncated; retry over HTTPS DNS.
                        if ((bytes[2].toInt() and 0x02) == 0) return bytes
                    }
                }
            } catch (_: Exception) {
                // UDP DNS may be blocked. Fall back to DNS-over-HTTPS below.
            }
        }
        return dohQuery(query, "1.1.1.1", "cloudflare-dns.com")
            ?: dohQuery(query, "8.8.8.8", "dns.google")
    }

    /** DNS-over-HTTPS fallback for networks that block ordinary UDP DNS. */
    private fun dohQuery(query: ByteArray, ip: String, host: String): ByteArray? {
        var raw: Socket? = null
        var tls: SSLSocket? = null
        return try {
            raw = Socket()
            if (!protect(raw)) throw IllegalStateException("Could not protect upstream socket")
            raw.connect(InetSocketAddress(InetAddress.getByName(ip), 443), 3000)
            raw.soTimeout = 3500
            tls = (SSLSocketFactory.getDefault() as SSLSocketFactory)
                .createSocket(raw, host, 443, true) as SSLSocket
            tls.sslParameters = tls.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
            tls.startHandshake()
            val requestHead = (
                "POST /dns-query HTTP/1.1\r\n" +
                "Host: $host\r\n" +
                "Accept: application/dns-message\r\n" +
                "Content-Type: application/dns-message\r\n" +
                "Content-Length: ${query.size}\r\n" +
                "Connection: close\r\n\r\n"
            ).toByteArray(Charsets.US_ASCII)
            tls.outputStream.write(requestHead)
            tls.outputStream.write(query)
            tls.outputStream.flush()

            val input = BufferedInputStream(tls.inputStream)
            val headerBytes = ByteArrayOutputStream()
            var state = 0
            while (headerBytes.size() < 16384 && state < 4) {
                val b = input.read()
                if (b < 0) return null
                headerBytes.write(b)
                state = when {
                    state == 0 && b == 13 -> 1
                    state == 1 && b == 10 -> 2
                    state == 2 && b == 13 -> 3
                    state == 3 && b == 10 -> 4
                    b == 13 -> 1
                    else -> 0
                }
            }
            if (state != 4) return null
            val headers = headerBytes.toString(Charsets.US_ASCII.name())
            if (!headers.startsWith("HTTP/1.1 200") && !headers.startsWith("HTTP/1.0 200")) return null
            val headerMap = headers.lineSequence().drop(1).mapNotNull { line ->
                val split = line.indexOf(':')
                if (split <= 0) null else line.substring(0, split).trim().lowercase(Locale.US) to line.substring(split + 1).trim()
            }.toMap()
            val transferEncoding = headerMap["transfer-encoding"]?.lowercase(Locale.US).orEmpty()
            if (transferEncoding.contains("chunked")) {
                val body = ByteArrayOutputStream()
                while (body.size() <= 65535) {
                    val sizeLine = readHttpLine(input) ?: return null
                    val sizeToken = sizeLine.substringBefore(';').trim()
                    val chunkSize = sizeToken.toIntOrNull(16) ?: return null
                    if (chunkSize < 0 || body.size() + chunkSize > 65535) return null
                    if (chunkSize == 0) {
                        // Consume optional trailer headers through the empty line.
                        while (true) { val trailer = readHttpLine(input) ?: return null; if (trailer.isEmpty()) break }
                        break
                    }
                    val chunk = ByteArray(chunkSize)
                    var read = 0
                    while (read < chunkSize) { val n = input.read(chunk, read, chunkSize - read); if (n < 0) return null; read += n }
                    body.write(chunk)
                    if (input.read() != 13 || input.read() != 10) return null
                }
                body.toByteArray().takeIf { it.size >= 12 }
            } else {
                val contentLength = headerMap["content-length"]?.toIntOrNull() ?: return null
                if (contentLength !in 12..65535) return null
                val body = ByteArray(contentLength)
                var offset = 0
                while (offset < body.size) { val n = input.read(body, offset, body.size - offset); if (n < 0) return null; offset += n }
                body
            }
        } catch (_: Exception) {
            null
        } finally {
            try { tls?.close() } catch (_: Exception) {}
            try { raw?.close() } catch (_: Exception) {}
        }
    }

    private fun readHttpLine(input: BufferedInputStream): String? {
        val out = ByteArrayOutputStream()
        var previous = -1
        while (out.size() < 4096) {
            val value = input.read()
            if (value < 0) return null
            if (previous == 13 && value == 10) {
                val bytes = out.toByteArray()
                return String(bytes, 0, (bytes.size - 1).coerceAtLeast(0), Charsets.US_ASCII)
            }
            out.write(value)
            previous = value
        }
        return null
    }

    private fun readQuestionName(dns: ByteArray): String? {
        if (dns.size < 17) return null
        var offset = 12
        val labels = ArrayList<String>()
        while (offset < dns.size) {
            val size = dns[offset++].toInt() and 0xff
            if (size == 0) break
            if (size > 63 || offset + size > dns.size) return null
            labels.add(String(dns, offset, size, Charsets.US_ASCII))
            offset += size
        }
        if (labels.isEmpty() || offset + 4 > dns.size) return null
        return labels.joinToString(".").trimEnd('.').lowercase(Locale.US)
    }

    private fun isBlocked(domain: String): Boolean = BLOCKED_DOMAINS.any { domain == it || domain.endsWith(".$it") }

    private fun makeBlockedResponse(query: ByteArray): ByteArray {
        val out = query.copyOf()
        // QR=1, preserve opcode and RD, set RA=1 and RCODE=NXDOMAIN; no answer records.
        out[2] = ((out[2].toInt() and 0x01) or 0x80).toByte()
        out[3] = ((out[3].toInt() and 0x70) or 0x80 or 0x03).toByte()
        out[6] = 0; out[7] = 0
        out[8] = 0; out[9] = 0
        out[10] = 0; out[11] = 0
        return out
    }

    private fun makeServFailResponse(query: ByteArray): ByteArray {
        val out = query.copyOf()
        out[2] = ((out[2].toInt() and 0x01) or 0x80).toByte()
        out[3] = ((out[3].toInt() and 0x70) or 0x80 or 0x02).toByte()
        out[6] = 0; out[7] = 0; out[8] = 0; out[9] = 0; out[10] = 0; out[11] = 0
        return out
    }

    private fun buildIpv4UdpResponse(original: ByteArray, ipHeaderLength: Int, clientPort: Int, dns: ByteArray): ByteArray {
        val totalLength = 20 + 8 + dns.size
        val out = ByteArray(totalLength)
        out[0] = 0x45
        out[1] = 0
        put16(out, 2, totalLength)
        put16(out, 4, 0)
        put16(out, 6, 0x4000) // Don't fragment
        out[8] = 64
        out[9] = 17
        // Response source is virtual DNS IP; destination is original client IP.
        System.arraycopy(original, 16, out, 12, 4)
        System.arraycopy(original, 12, out, 16, 4)
        put16(out, 10, checksum(out, 0, 20))
        put16(out, 20, 53)
        put16(out, 22, clientPort)
        put16(out, 24, 8 + dns.size)
        put16(out, 26, 0) // IPv4 permits an omitted UDP checksum.
        System.arraycopy(dns, 0, out, 28, dns.size)
        return out
    }

    private fun checksum(data: ByteArray, offset: Int, length: Int): Int {
        var sum = 0L
        var i = offset
        val end = offset + length
        while (i + 1 < end) {
            sum += (((data[i].toInt() and 0xff) shl 8) or (data[i + 1].toInt() and 0xff)).toLong()
            i += 2
        }
        if (i < end) sum += ((data[i].toInt() and 0xff) shl 8).toLong()
        while ((sum shr 16) != 0L) sum = (sum and 0xffff) + (sum shr 16)
        return sum.inv().toInt() and 0xffff
    }

    private fun u16(data: ByteArray, offset: Int): Int = ((data[offset].toInt() and 0xff) shl 8) or (data[offset + 1].toInt() and 0xff)
    private fun put16(data: ByteArray, offset: Int, value: Int) {
        data[offset] = (value ushr 8).toByte()
        data[offset + 1] = value.toByte()
    }
    private fun ipv4String(data: ByteArray, offset: Int): String = (0..3).joinToString(".") { (data[offset + it].toInt() and 0xff).toString() }

    private fun stopFiltering() {
        stopping.set(true)
        running = false
        val oldInterface = vpnInterface
        vpnInterface = null
        try { oldInterface?.close() } catch (_: Exception) {}
        try { packetThread?.interrupt() } catch (_: Exception) {}
        packetThread = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onRevoke() {
        stopFiltering()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopping.set(true)
        running = false
        try { vpnInterface?.close() } catch (_: Exception) {}
        vpnInterface = null
        packetThread?.interrupt()
        packetThread = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "SK Ad Blocker", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shows when DNS ad filtering is active"
                }
            )
        }
    }

    private fun buildNotification(): Notification {
        val stopIntent = Intent(this, AdBlockVpnService::class.java).setAction(ACTION_STOP)
        val pending = PendingIntent.getService(this, 101, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, CHANNEL_ID) else Notification.Builder(this)
        return builder.setContentTitle("SK Ad Blocker")
            .setContentText("DNS ad filtering active · other traffic stays online")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop", pending).build())
            .build()
    }

    companion object {
        @Volatile var running: Boolean = false
            private set
        const val ACTION_STOP = "com.skkaushal.adblocker.STOP"
        private const val CHANNEL_ID = "sk_ad_blocker"
        private const val NOTIFICATION_ID = 100
        private const val VPN_ADDRESS = "10.8.0.2"
        private const val DNS_ADDRESS = "10.8.0.1"
        private val BLOCKED_DOMAINS = setOf(
            "doubleclick.net", "googlesyndication.com", "googleadservices.com", "adservice.google.com",
            "pagead2.googlesyndication.com", "adnxs.com", "adform.net", "adsrvr.org", "taboola.com",
            "outbrain.com", "scorecardresearch.com", "zedo.com", "adcolony.com", "applovin.com",
            "unityads.unity3d.com", "vungle.com", "inmobi.com", "mopub.com", "criteo.com",
            "branch.io", "adjust.com", "appsflyer.com", "mixpanel.com", "segment.io"
        )
    }
}
