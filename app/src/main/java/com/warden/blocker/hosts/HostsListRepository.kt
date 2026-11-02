package com.warden.blocker.hosts

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.warden.blocker.block.TimeWindows
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URL
import java.net.UnknownHostException
import java.util.UUID
import javax.net.ssl.HttpsURLConnection
import kotlin.coroutines.coroutineContext

/** A user-facing import failure; [message] is shown as-is in the UI. */
class ImportException(message: String) : IOException(message)

/**
 * Imported hosts/blocklists: metadata in `filesDir/hostlists/sources.json`, domains in one
 * text file per list. Downloading and parsing always happen on [Dispatchers.IO]; only one
 * import runs at a time. The VPN asks [activeDomainSet] for a compact set of all domains
 * from enabled lists and reloads whenever [activeKey] changes.
 */
class HostsListRepository(
    private val context: Context,
    /** Opens a list download (stream + length or -1); replaceable in tests. */
    private val openUrl: (String) -> Pair<InputStream, Long> = { openHttps(it) },
) {

    private val dir: File by lazy { File(context.filesDir, "hostlists").apply { mkdirs() } }
    private val metaFile: File get() = File(dir, "sources.json")
    private fun domainsFile(id: String) = File(dir, "$id.txt")

    /** App-lifetime scope for imports started from the UI (they outlive the screen). */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val metaLock = Mutex()
    private val importLock = Mutex()
    private val cacheLock = Mutex()
    @Volatile private var loaded = false

    private val _sources = MutableStateFlow<List<HostsSource>>(emptyList())
    val sources: StateFlow<List<HostsSource>> = _sources.asStateFlow()

    private val _progress = MutableStateFlow<ImportProgress?>(null)
    val progress: StateFlow<ImportProgress?> = _progress.asStateFlow()

    /** Changes exactly when the set of blocked list-domains changes (toggle, refresh, delete,
     *  a new "from … / until …" span; the span starting or ending is noticed by the VPN's clock). */
    val activeKey: Flow<List<List<Any?>>> =
        _sources.map { list ->
            list.filter { it.enabled }.map { listOf(it.id, it.updatedAt, it.activeFrom, it.activeUntil) }
        }.distinctUntilChanged()

    @Volatile private var cacheKey: List<Pair<String, Long>>? = null
    @Volatile private var cache: DomainHashSet? = null

    suspend fun ensureLoaded() {
        if (loaded) return
        metaLock.withLock {
            if (loaded) return
            _sources.value = withContext(Dispatchers.IO) { readMeta() }
            loaded = true
        }
    }

    // --- Import ---

    suspend fun importFile(uri: Uri): Result<HostsSource> = runImport {
        val resolver = context.contentResolver
        var name = "Imported file"
        var size = -1L
        withContext(Dispatchers.IO) {
            runCatching {
                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        c.getString(0)?.let { name = it }
                        if (!c.isNull(1)) size = c.getLong(1)
                    }
                }
            }
        }
        if (size > MAX_BYTES) throw ImportException(TOO_LARGE)
        val source = HostsSource(id = newId(), name = name, url = null)
        val result = ingest(source) {
            val input = resolver.openInputStream(uri) ?: throw ImportException("Couldn't open the file.")
            input to size
        }
        updateMeta { it + result }
        result
    }

    /** Download [rawUrl] as a new list, or refresh it if a list with that URL already exists. */
    suspend fun addUrl(rawUrl: String, name: String? = null): Result<HostsSource> {
        val url = normalizeUrl(rawUrl) ?: return Result.failure(ImportException("Please enter a valid https:// link."))
        ensureLoaded()
        _sources.value.firstOrNull { it.url == url }?.let { return refresh(it.id) }
        return runImport {
            // A double tap queues a second import behind the lock; by now the first may have
            // added this URL, so check again instead of creating the same list twice.
            _sources.value.firstOrNull { it.url == url }?.let { return@runImport it }
            val source = HostsSource(id = newId(), name = name ?: defaultName(url), url = url)
            val result = ingest(source) { openUrl(url) }
            updateMeta { it + result }
            result
        }
    }

    /** Re-download a URL list. On failure the old domains stay active and the error is kept. */
    suspend fun refresh(id: String): Result<HostsSource> {
        ensureLoaded()
        val source = _sources.value.firstOrNull { it.id == id }
            ?: return Result.failure(ImportException("This list no longer exists."))
        val url = source.url ?: return Result.failure(ImportException("Files from the phone can't be refreshed — import the file again."))
        val result = runImport { ingest(source) { openUrl(url) } }
        updateMeta { list ->
            list.map { s ->
                if (s.id != id) s
                else result.fold({ it.copy(enabled = s.enabled) }, { e -> s.copy(lastError = e.message) })
            }
        }
        // Deleted while downloading? Then don't leave the fresh file behind.
        if (_sources.value.none { it.id == id }) domainsFile(id).delete()
        return result
    }

    /** Refresh every URL list (used by the weekly worker). True when all succeeded. */
    suspend fun refreshAllUrls(): Boolean {
        ensureLoaded()
        var ok = true
        for (s in _sources.value.filter { it.url != null }) {
            if (refresh(s.id).isFailure) ok = false
        }
        return ok
    }

    suspend fun setEnabled(id: String, enabled: Boolean) =
        updateMeta { list -> list.map { if (it.id == id) it.copy(enabled = enabled) else it } }

    /** Limit when list [id] blocks (epoch millis, null = no limit). */
    suspend fun setActiveSpan(id: String, from: Long?, until: Long?) =
        updateMeta { list -> list.map { if (it.id == id) it.copy(activeFrom = from, activeUntil = until) else it } }

    suspend fun delete(id: String) {
        updateMeta { list -> list.filterNot { it.id == id } }
        withContext(Dispatchers.IO) { domainsFile(id).delete() }
    }

    // --- Blocking ---

    /**
     * All domains of enabled lists (inside their time span, if set) as one compact set (null
     * if none). Built from the files on first use and cached until that set of lists changes,
     * so repeated VPN refreshes are free — and a new set object means the lists changed.
     */
    suspend fun activeDomainSet(now: Long = System.currentTimeMillis()): DomainHashSet? {
        ensureLoaded()
        return cacheLock.withLock {
            val active = _sources.value.filter {
                it.enabled && it.domainCount > 0 && TimeWindows.inEffect(it.activeFrom, it.activeUntil, now)
            }
            val key = active.map { it.id to it.updatedAt }
            if (key == cacheKey) return@withLock cache
            cache = null // let the old set be collected before building the new one
            val set = if (active.isEmpty()) null else withContext(Dispatchers.IO) {
                DomainHashSet(active.sumOf { it.domainCount }).also { set ->
                    for (s in active) {
                        val f = domainsFile(s.id)
                        if (f.exists()) f.forEachLine { line -> if (line.isNotEmpty()) set.add(line) }
                    }
                }
            }
            cache = set
            cacheKey = key
            set
        }
    }

    /** Drop the in-memory set (VPN stopped); it's rebuilt from disk on next use. */
    fun releaseCache() {
        if (!cacheLock.tryLock()) return
        try { cache = null; cacheKey = null } finally { cacheLock.unlock() }
    }

    // --- Internals ---

    private suspend fun <T> runImport(block: suspend () -> T): Result<T> {
        ensureLoaded()
        return importLock.withLock {
            try {
                Result.success(block())
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(ImportException(friendlyMessage(e)))
            } finally {
                _progress.value = null
            }
        }
    }

    /**
     * Stream [open]'s content through the parser into `<id>.txt` (via a temp file, so a
     * failed refresh never destroys the previous list). Duplicates are skipped with a
     * hash set, so memory stays small even for 200k-line lists.
     */
    private suspend fun ingest(source: HostsSource, open: () -> Pair<InputStream, Long>): HostsSource =
        withContext(Dispatchers.IO) {
            _progress.value = ImportProgress(source.name)
            val tmp = File(dir, "${source.id}.tmp")
            try {
                val (raw, total) = open()
                val counted = LimitedInputStream(raw, MAX_BYTES, System.currentTimeMillis() + MAX_DURATION_MS)
                val seen = DomainHashSet(4096)
                var lines = 0
                BoundedLineReader(InputStreamReader(counted, Charsets.UTF_8)).use { reader ->
                    tmp.bufferedWriter().use { out ->
                        while (true) {
                            val line = reader.readLine() ?: break
                            HostsParser.parseLine(line) { d -> if (seen.add(d)) { out.write(d); out.write('\n'.code) } }
                            if (++lines % 4000 == 0) {
                                coroutineContext.ensureActive()
                                _progress.value = ImportProgress(source.name, counted.count, total, seen.size)
                            }
                        }
                    }
                }
                if (seen.size == 0) throw ImportException("No domains found. Is this a hosts file or a domain list?")
                val target = domainsFile(source.id)
                if (!tmp.renameTo(target)) {
                    tmp.copyTo(target, overwrite = true)
                }
                source.copy(domainCount = seen.size, updatedAt = System.currentTimeMillis(), lastError = null)
            } finally {
                tmp.delete()
            }
        }

    private suspend fun updateMeta(change: (List<HostsSource>) -> List<HostsSource>) {
        ensureLoaded()
        val updated = metaLock.withLock {
            val next = change(_sources.value)
            withContext(Dispatchers.IO) { writeMeta(next) }
            _sources.value = next
            next
        }
        HostsRefreshWorker.sync(context, updated.any { it.url != null })
    }

    private fun readMeta(): List<HostsSource> = runCatching {
        if (!metaFile.exists()) return emptyList()
        val arr = JSONArray(metaFile.readText())
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            HostsSource(
                id = o.getString("id"),
                name = o.getString("name"),
                url = if (o.isNull("url")) null else o.getString("url"),
                enabled = o.optBoolean("enabled", true),
                domainCount = o.optInt("domainCount", 0),
                updatedAt = o.optLong("updatedAt", 0L),
                lastError = if (o.isNull("lastError")) null else o.optString("lastError"),
                activeFrom = if (o.isNull("activeFrom")) null else o.optLong("activeFrom"),
                activeUntil = if (o.isNull("activeUntil")) null else o.optLong("activeUntil"),
            )
        }
    }.getOrDefault(emptyList())

    private fun writeMeta(list: List<HostsSource>) {
        val arr = JSONArray()
        list.forEach { s ->
            arr.put(
                JSONObject()
                    .put("id", s.id)
                    .put("name", s.name)
                    .put("url", s.url ?: JSONObject.NULL)
                    .put("enabled", s.enabled)
                    .put("domainCount", s.domainCount)
                    .put("updatedAt", s.updatedAt)
                    .put("lastError", s.lastError ?: JSONObject.NULL)
                    .put("activeFrom", s.activeFrom ?: JSONObject.NULL)
                    .put("activeUntil", s.activeUntil ?: JSONObject.NULL),
            )
        }
        val tmp = File(dir, "sources.json.tmp")
        tmp.writeText(arr.toString())
        if (!tmp.renameTo(metaFile)) { tmp.copyTo(metaFile, overwrite = true); tmp.delete() }
    }

    private fun friendlyMessage(e: Exception): String = when (e) {
        is ImportException -> e.message ?: "Import failed."
        is UnknownHostException -> "No internet connection, or the server name is wrong."
        is SocketTimeoutException -> "The server took too long to answer."
        is javax.net.ssl.SSLException -> "Secure connection failed."
        is java.io.FileNotFoundException -> "File or page not found."
        else -> "Import failed (${e.javaClass.simpleName})."
    }

    /** Counts bytes and aborts past [max] bytes or after [deadline] (wall-clock millis). */
    private class LimitedInputStream(inner: InputStream, private val max: Long, private val deadline: Long) :
        FilterInputStream(inner) {
        var count = 0L
            private set

        override fun read(): Int = super.read().also { if (it >= 0) add(1) }

        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) add(it) }

        private fun add(n: Int) {
            count += n
            if (count > max) throw ImportException(TOO_LARGE)
            if (System.currentTimeMillis() > deadline) throw ImportException("The download took too long.")
        }
    }

    companion object {
        const val MAX_BYTES = 30L * 1024 * 1024
        private const val MAX_DURATION_MS = 3 * 60_000L
        private const val TOO_LARGE = "The list is larger than 30 MB."

        private fun newId() = UUID.randomUUID().toString()

        private fun openHttps(url: String): Pair<InputStream, Long> {
            val conn = URL(url).openConnection() as? HttpsURLConnection
                ?: throw ImportException("Only https:// links are supported.")
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.instanceFollowRedirects = true // never downgrades https → http
            conn.setRequestProperty("User-Agent", "Warden")
            val code = conn.responseCode
            if (code !in 200..299) {
                conn.disconnect()
                throw ImportException("The server answered with error $code.")
            }
            val length = conn.contentLengthLong
            if (length > MAX_BYTES) { conn.disconnect(); throw ImportException(TOO_LARGE) }
            return conn.inputStream to length
        }

        /** Accepts `example.com/list.txt` or `https://…`; rejects anything that isn't https. */
        fun normalizeUrl(input: String): String? {
            val trimmed = input.trim()
            if (trimmed.isEmpty() || trimmed.any { it.isWhitespace() }) return null
            val withScheme = if ("://" in trimmed) trimmed else "https://$trimmed"
            val uri = runCatching { URI(withScheme) }.getOrNull() ?: return null
            if (!uri.scheme.equals("https", ignoreCase = true) || uri.host.isNullOrBlank()) return null
            return withScheme
        }

        fun defaultName(url: String): String = runCatching {
            val uri = URI(url)
            (uri.host + (uri.path ?: "")).removeSuffix("/")
        }.getOrDefault(url)
    }
}
