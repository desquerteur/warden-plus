package com.warden.blocker.vpn

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.warden.blocker.MainActivity
import com.warden.blocker.R
import com.warden.blocker.WardenApp
import com.warden.blocker.block.BlockStateClock
import com.warden.blocker.hosts.DomainMatcher
import com.warden.blocker.stats.StatsRecorder
import com.warden.blocker.wardenContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer

/**
 * Local, no-root VPN that captures DNS traffic and blackholes queries for blocked
 * domains. Non-blocked queries are forwarded to an upstream resolver and NAT'd back.
 * Only DNS is routed into the tunnel, so all other traffic is untouched.
 */
class WardenVpnService : VpnService() {

    private var tunnel: ParcelFileDescriptor? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loopJob: Job? = null

    private var watchJob: Job? = null

    @Volatile private var blocklist: DomainMatcher = DomainMatcher.EMPTY

    /** Whether [blocklist] was built while blocking was enforced (null = not built yet). */
    @Volatile private var builtForActive: Boolean? = null
    private val refreshLock = Mutex()

    /** The device's real DNS resolvers, captured before the tunnel takes over the active
     *  network. We forward non-blocked queries here so Warden never redirects your DNS to a
     *  third party of its own choosing. */
    @Volatile private var upstreamDns: List<InetAddress> = emptyList()
    private val fallbackDns: InetAddress by lazy { InetAddress.getByName(FALLBACK_DNS) }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopTunnel(); stopSelf(); return START_NOT_STICKY }
            else -> startTunnel()
        }
        return START_STICKY
    }

    private fun startTunnel() {
        if (tunnel != null) { scope.launch { refreshBlocklist() }; return }
        startForeground(NOTIF_ID, buildNotification())

        // Capture the real resolvers BEFORE establish() — afterwards the active network is us.
        upstreamDns = systemDnsServers()

        val builder = Builder()
            .setSession("Warden")
            .addAddress(VIRTUAL_ADDRESS, 24)
            .addDnsServer(VIRTUAL_DNS)
            .addRoute(VIRTUAL_DNS, 32) // only DNS to our virtual resolver enters the tunnel
            .setBlocking(true)
        runCatching { builder.addDisallowedApplication(packageName) } // never filter ourselves

        // establish() returns null without consent and throws when the system refuses the tunnel
        // (e.g. another app holds "Always-on VPN"). Never crash for that: stop, and Home shows
        // "Protection paused" because blocking is on but the filter isn't running.
        tunnel = runCatching { builder.establish() }
            .onFailure { Log.e(TAG, "VPN establish() failed", it) }
            .getOrNull() ?: run {
                Log.e(TAG, "VPN establish() returned no tunnel (consent not granted?)")
                stopSelf(); return
            }

        _running.value = true
        loopJob = scope.launch {
            refreshBlocklist()
            runPacketLoop(tunnel!!)
        }
        watchJob = scope.launch { watchBlocklistChanges() }
    }

    private suspend fun refreshBlocklist(): Unit = refreshLock.withLock {
        val c = wardenContainer
        val active = runCatching { c.blockEngine.isBlockingActiveNow() }
            .onFailure { Log.e(TAG, "Blocklist refresh failed", it) }
            .getOrNull() ?: return@withLock
        blocklist = runCatching { c.blockEngine.blocklist(active).also { builtForActive = active } }
            .onFailure { Log.e(TAG, "Blocklist refresh failed", it) }
            .getOrDefault(blocklist)
        // Outside a schedule, still load the hosts-list domains (cached until a list changes),
        // so switching on when a schedule starts is instant.
        if (!active) runCatching { c.hostsLists.activeDomainSet() }
    }

    /** Reload when the manual list, an imported hosts list, the DoH switch or anything that
     *  decides whether blocking is enforced changes, so edits take effect immediately without
     *  restarting the tunnel — and when a schedule or focus session starts or ends. */
    @OptIn(FlowPreview::class)
    private suspend fun watchBlocklistChanges() = coroutineScope {
        val c = wardenContainer
        launch { watchScheduleClock() }
        val inputs = listOf<Flow<Any?>>(
            c.repository.items, c.repository.schedules, c.hostsLists.activeKey,
            c.settings.blockDoh, c.settings.masterEnabled, c.settings.alwaysOn, c.settings.focusEndsAt,
        ).map { it.distinctUntilChanged() } // settings flows re-emit on every unrelated pref write
        combine(inputs) { }
            .drop(1) // the initial values are covered by the refresh before the packet loop
            .debounce(300)
            .collect { refreshBlocklist() }
    }

    /** Re-check at every minute boundary (and at a focus session's exact end) and rebuild
     *  only when enforcement flipped, so a running VPN follows its schedules. */
    private suspend fun watchScheduleClock() {
        val c = wardenContainer
        while (true) {
            val focusEndsAt = runCatching { c.settings.focusEndsAt.first() }.getOrDefault(0L)
            delay(BlockStateClock.delayUntilNextCheck(System.currentTimeMillis(), focusEndsAt))
            val active = runCatching { c.blockEngine.isBlockingActiveNow() }.getOrNull() ?: continue
            if (BlockStateClock.needsRebuild(builtForActive, active)) refreshBlocklist()
        }
    }

    private fun runPacketLoop(pfd: ParcelFileDescriptor) {
        // Guard the whole loop: a malformed packet or transient socket error must never
        // crash the app — it just stops filtering until blocking is toggled again.
        val input = FileInputStream(pfd.fileDescriptor)
        val output = FileOutputStream(pfd.fileDescriptor)
        val packet = ByteArray(MTU)

        try {
            while (scope.isActive) {
                val read = try { input.read(packet) } catch (e: Exception) { break }
                if (read <= 0) continue
                val parsed = try { DnsPacket.parse(packet, read) } catch (e: Exception) { null } ?: continue
                if (parsed.dstPort != DnsPacket.DNS_PORT) continue

                val name = parsed.qname ?: continue
                if (isBlocked(name)) {
                    // Never log the domain — that would leak browsing history into logcat.
                    runCatching { output.write(DnsPacket.buildSinkholeResponse(packet, read, parsed)) }
                    // Only a per-day count, no domain; covers manual entries and hosts lists alike.
                    StatsRecorder.get(this@WardenVpnService).recordDomainBlocked()
                } else {
                    forward(packet, parsed, output)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Packet loop terminated unexpectedly", e)
        }
    }

    /** A domain is blocked if it equals or is a subdomain of any blocked entry. */
    private fun isBlocked(host: String): Boolean = blocklist.isBlocked(host)

    /**
     * Forward one DNS query to the device's resolver and write the reply back into the tun.
     * Uses a fresh **connected** socket per query so only the intended resolver's replies are
     * accepted, and verifies the DNS transaction ID — together these block off-path response
     * spoofing / cache-poisoning on a hostile network.
     */
    private fun forward(packet: ByteArray, parsed: DnsPacket.Parsed, output: FileOutputStream) {
        val query = packet.copyOfRange(parsed.dnsPayloadOffset, parsed.dnsPayloadOffset + parsed.dnsPayloadLen)
        val target = upstreamDns.firstOrNull() ?: fallbackDns
        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket()
            protect(socket)
            socket.connect(target, DnsPacket.DNS_PORT) // only accept replies from this resolver
            socket.soTimeout = 3000
            socket.send(DatagramPacket(query, query.size))

            val buf = ByteArray(MTU)
            val replyPacket = DatagramPacket(buf, buf.size)
            socket.receive(replyPacket)
            val replyLen = replyPacket.length
            // Transaction-ID must match the query, else it's a spoofed / stray reply — drop it.
            if (replyLen < 2 || buf[0] != query[0] || buf[1] != query[1]) return
            output.write(wrapReply(packet, parsed, buf.copyOf(replyLen)))
        } catch (e: Exception) {
            // Don't log the domain (privacy); the exception class is enough to diagnose.
            Log.w(TAG, "DNS forward failed: ${e.javaClass.simpleName}")
        } finally {
            runCatching { socket?.close() }
        }
    }

    /** The device's DNS servers on the underlying network (empty if unavailable). */
    private fun systemDnsServers(): List<InetAddress> = runCatching {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return emptyList()
        val net = cm.activeNetwork ?: return emptyList()
        cm.getLinkProperties(net)?.dnsServers?.filterNot { it.isLoopbackAddress } ?: emptyList()
    }.getOrDefault(emptyList())

    /** Wrap a raw upstream DNS reply in IP/UDP headers pointed back at the querying app. */
    private fun wrapReply(request: ByteArray, parsed: DnsPacket.Parsed, dnsReply: ByteArray): ByteArray {
        val totalLen = parsed.ipHeaderLen + 8 + dnsReply.size
        val out = ByteBuffer.allocate(totalLen)
        out.put(request, 0, parsed.ipHeaderLen)
        out.putShort(2, totalLen.toShort())
        for (k in 0 until 4) out.put(12 + k, parsed.dstIp[k])
        for (k in 0 until 4) out.put(16 + k, parsed.srcIp[k])
        out.putShort(10, 0)
        // reuse DnsPacket's IP checksum by round-tripping through a sinkhole-style fix
        val arr = out.array()
        ipChecksum(arr, parsed.ipHeaderLen)
        val u = parsed.ipHeaderLen
        out.putShort(u, parsed.dstPort.toShort())
        out.putShort(u + 2, parsed.srcPort.toShort())
        out.putShort(u + 4, (8 + dnsReply.size).toShort())
        out.putShort(u + 6, 0)
        out.position(parsed.ipHeaderLen + 8)
        out.put(dnsReply)
        return arr
    }

    private fun ipChecksum(buf: ByteArray, headerLen: Int) {
        var sum = 0L
        var i = 0
        while (i < headerLen) {
            sum += ((buf[i].toInt() and 0xFF) shl 8) or (buf[i + 1].toInt() and 0xFF)
            i += 2
        }
        while (sum shr 16 != 0L) sum = (sum and 0xFFFF) + (sum shr 16)
        val c = sum.inv().toInt() and 0xFFFF
        buf[10] = (c ushr 8).toByte()
        buf[11] = (c and 0xFF).toByte()
    }

    private fun stopTunnel() {
        _running.value = false
        loopJob?.cancel()
        watchJob?.cancel()
        runCatching { tunnel?.close() }
        tunnel = null
        blocklist = DomainMatcher.EMPTY
        builtForActive = null
        wardenContainer.hostsLists.releaseCache()
    }

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, WardenApp.VPN_CHANNEL_ID)
            .setContentTitle(getString(R.string.vpn_notification_title))
            .setContentText(getString(R.string.vpn_notification_text))
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        _running.value = false
        scope.cancel()
        runCatching { tunnel?.close() }
        wardenContainer.hostsLists.releaseCache()
        super.onDestroy()
    }

    companion object {
        private val _running = MutableStateFlow(false)

        /** Whether the filtering tunnel is currently up — observed by the Home health indicator
         *  so users are never *silently* unblocked when the OS kills the service. */
        val runningState: StateFlow<Boolean> = _running.asStateFlow()
        val running: Boolean get() = _running.value

        private const val TAG = "WardenVpn"
        const val ACTION_START = "com.warden.blocker.VPN_START"
        const val ACTION_STOP = "com.warden.blocker.VPN_STOP"
        private const val VIRTUAL_ADDRESS = "10.111.222.1"
        private const val VIRTUAL_DNS = "10.111.222.2"
        // Used only if the OS exposes no resolver (rare); prefer the device's own DNS.
        private const val FALLBACK_DNS = "1.1.1.1"
        private const val MTU = 32767
        private const val NOTIF_ID = 1001
    }
}
