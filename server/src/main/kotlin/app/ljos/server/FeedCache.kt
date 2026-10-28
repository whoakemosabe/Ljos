package app.ljos.server

import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Raw feed bodies kept in memory, fetched only when someone asks and the copy is stale.
 * Nothing runs in the background, so an idle server makes no requests at all.
 *
 *  - One fetch per key at a time: concurrent requests for the same stale key wait for it.
 *  - A failed fetch keeps serving the last good body, and isn't retried for [retryAfterMs].
 *  - At most [maxEntries] bodies; the least recently used go first.
 */
class FeedCache(
    private val fetch: (String) -> String,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxEntries: Int = 2000,
    private val retryAfterMs: Long = 60_000L,
) {
    class Entry(val body: String, val fetchedAt: Long)

    private val entries = object : LinkedHashMap<String, Entry>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?): Boolean = size > maxEntries
    }
    private val failedAt = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val errors = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val stripes = Array(64) { Any() }

    /** The body for [key], refetched from [url] once older than [maxAgeMs]; null if never fetched. */
    fun get(key: String, url: String, maxAgeMs: Long, valid: (String) -> Boolean): Entry? {
        val cached = peek(key)
        if (cached != null && clock() - cached.fetchedAt < maxAgeMs) return cached
        val lock = stripes[(key.hashCode() and 0x7fffffff) % stripes.size]
        synchronized(lock) {
            val again = peek(key)
            if (again != null && clock() - again.fetchedAt < maxAgeMs) return again
            val lastFail = failedAt[key]
            if (lastFail != null && clock() - lastFail < retryAfterMs) return again
            try {
                val body = fetch(url)
                val ok = try { valid(body) } catch (e: Exception) { false }
                if (ok) {
                    val entry = Entry(body, clock())
                    synchronized(entries) { entries[key] = entry }
                    failedAt.remove(key)
                    errors.remove(key)
                    return entry
                }
                errors[key] = "unexpected data"
            } catch (e: Exception) {
                errors[key] = e.message ?: e.javaClass.simpleName
            }
            failedAt[key] = clock()
            return again
        }
    }

    fun peek(key: String): Entry? = synchronized(entries) { entries[key] }

    /** Current fetch problems, by key. */
    fun errors(): Map<String, String> = HashMap(errors)
}

/** Plain HTTPS GET with an identifying User-Agent, as MET Norway and Open-Meteo ask for. */
object Http {
    const val USER_AGENT = "Ljos/1.0 (+https://github.com/whoakemosabe/Ljos)"

    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    fun get(url: String): String {
        val request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(25))
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .GET()
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) throw IOException("HTTP ${response.statusCode()}")
        return response.body()
    }
}
