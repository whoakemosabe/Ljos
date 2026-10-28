package app.ljos.server

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

/** Applied to every response. Everything the page loads comes from this server. */
private val SECURITY_HEADERS = mapOf(
    "Content-Security-Policy" to "default-src 'self'; img-src 'self' data: https://server.arcgisonline.com https://tile.openstreetmap.org; style-src 'self'; script-src 'self'; " +
        "connect-src 'self'; worker-src 'self'; manifest-src 'self'; base-uri 'none'; frame-ancestors 'none'; form-action 'none'",
    "X-Content-Type-Options" to "nosniff",
    "Referrer-Policy" to "no-referrer",
    "Permissions-Policy" to "geolocation=(self), camera=(), microphone=()",
)

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    val app = App(Forecaster(FeedCache(Http::get)), Places.fromResource())
    val server = HttpServer.create(InetSocketAddress(port), 0)
    server.createContext("/") { ex -> handle(app, ex) }
    server.executor = Executors.newFixedThreadPool(16)
    server.start()
    println("Ljós server listening on :$port")
}

private fun handle(app: App, ex: HttpExchange) {
    try {
        val r = try {
            app.respond(ex.requestMethod, ex.requestURI.rawPath, ex.requestURI.rawQuery)
        } catch (e: Exception) {
            // Details go to the log only; the client gets a generic error.
            System.err.println("Error on ${ex.requestURI}: $e")
            Response(500, "application/json; charset=utf-8",
                Api.error("INTERNAL", "Something went wrong").toString().toByteArray(StandardCharsets.UTF_8))
        }
        val (bytes, gzipped) = App.maybeGzip(r, ex.requestHeaders.getFirst("Accept-Encoding"))
        val h = ex.responseHeaders
        SECURITY_HEADERS.forEach { (k, v) -> h.set(k, v) }
        h.set("Content-Type", r.type)
        h.set("Cache-Control", r.cache)
        h.set("Vary", "Accept-Encoding")
        if (gzipped) h.set("Content-Encoding", "gzip")
        // A length of 0 means "chunked" to HttpServer; an empty or HEAD response must send -1.
        if (ex.requestMethod == "HEAD" || bytes.isEmpty()) {
            ex.sendResponseHeaders(r.status, -1)
        } else {
            ex.sendResponseHeaders(r.status, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
    } finally {
        ex.close()
    }
}
