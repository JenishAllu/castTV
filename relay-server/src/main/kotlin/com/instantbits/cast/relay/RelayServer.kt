package com.instantbits.cast.relay

import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.plugins.callloging.*
import io.ktor.server.plugins.hsts.*
import io.ktor.server.plugins.conditionalheaders.*
import io.ktor.server.plugins.compression.*
import io.ktor.server.request.*
import io.ktor.http.*
import kotlinx.serialization.*
import kotlinx.serialization.json.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URL
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import kotlin.concurrent.thread

/**
 * Relay Server (Tier 2) - Optional component for sites that hide video URLs.
 *
 * This server runs separately from the Android app. It:
 * 1. Receives a URL from the app
 * 2. Loads the page in a headless browser (or parses HTML)
 * 3. Extracts/decodes the video stream URL
 * 4. Returns the direct stream URL to the app for casting
 *
 * Run with: ./gradlew :relay-server:run
 *
 * NOTE: This is a basic implementation. For production use with DRM/encrypted streams,
 * you would need a full headless browser (Playwright/Puppeteer) and possibly re-encoding.
 */
fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    val host = System.getenv("HOST") ?: "0.0.0.0"

    embeddedServer(Netty, port = port, host = host, module = Application::module)
        .start(wait = true)
}

fun Application.module() {
    install(ContentNegotiation) {
        json()
    }
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse(cause.message ?: "Internal error"))
        }
    }
    install(CallLogging)
    install(HSTS)
    install(ConditionalHeaders)
    install(Compression)

    routing {
        get("/health") {
            call.respond(HealthResponse("ok", System.currentTimeMillis()))
        }

        post("/extract") {
            val request = call.receive<ExtractRequest>()
            val result = extractVideoUrl(request.url)
            call.respond(result)
        }

        get("/extract") {
            val url = call.request.queryParameters["url"]
            if (url == null) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing 'url' parameter"))
                return@get
            }
            val result = extractVideoUrl(url)
            call.respond(result)
        }
    }
}

@Serializable
data class HealthResponse(
    val status: String,
    val timestamp: Long
)

@Serializable
data class ExtractRequest(
    val url: String,
    val userAgent: String? = null
)

@Serializable
data class ExtractResponse(
    val success: Boolean,
    val videoUrl: String? = null,
    val mimeType: String? = null,
    val title: String? = null,
    val thumbnail: String? = null,
    val error: String? = null,
    val source: String = "relay-server"
)

@Serializable
data class ErrorResponse(
    val error: String
)

/**
 * Extract video URL from a web page.
 * This is a server-side version of the VideoExtractor.
 */
fun extractVideoUrl(url: String): ExtractResponse {
    return try {
        // Validate URL
        val parsedUrl = URL(url)
        if (parsedUrl.protocol !in listOf("http", "https")) {
            return ExtractResponse(success = false, error = "Invalid protocol: ${parsedUrl.protocol}")
        }

        // Fetch page HTML
        val connection = Jsoup.connect(url)
            .userAgent("Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
            .timeout(15000)
            .followRedirects(true)
            .ignoreHttpErrors(true)
            .ignoreContentType(true)

        val response = connection.execute()
        if (response.statusCode() !in 200..299) {
            return ExtractResponse(success = false, error = "HTTP ${response.statusCode()}: ${response.statusMessage()}")
        }

        val doc = response.parse()
        val title = doc.title()

        // Extract video URLs using multiple strategies
        val candidates = mutableListOf<VideoCandidate>()

        // Strategy 1: <video> tags
        extractFromVideoTags(doc, candidates)

        // Strategy 2: Source tags
        extractFromSourceTags(doc, candidates)

        // Strategy 3: Script content regex
        extractFromScripts(doc, candidates)

        // Strategy 4: YouTube
        extractYouTube(doc, url, candidates)

        // Strategy 5: Vimeo
        extractVimeo(doc, candidates)

        // Strategy 6: Meta tags (Open Graph, Twitter)
        extractFromMetaTags(doc, candidates)

        // Strategy 7: JSON-LD structured data
        extractFromJsonLd(doc, candidates)

        // Deduplicate
        val seen = mutableSetOf<String>()
        val uniqueCandidates = candidates.filter { seen.add(it.url) }

        if (uniqueCandidates.isEmpty()) {
            return ExtractResponse(
                success = false,
                error = "No video found on page",
                title = title
            )
        }

        // Sort by priority (higher = better)
        val best = uniqueCandidates.maxByOrNull { it.priority } ?: uniqueCandidates[0]

        return ExtractResponse(
            success = true,
            videoUrl = best.url,
            mimeType = best.mimeType,
            title = title,
            thumbnail = extractThumbnail(doc),
            source = "relay-server"
        )
    } catch (e: Exception) {
        ExtractResponse(success = false, error = "Extraction failed: ${e.message}")
    }
}

data class VideoCandidate(
    val url: String,
    val mimeType: String,
    val type: String,
    val priority: Int
)

private fun extractFromVideoTags(doc: Document, candidates: MutableList<VideoCandidate>) {
    doc.select("video").forEach { video ->
        video.attr("src").takeIf { it.isNotBlank() }?.let { src ->
            candidates.add(VideoCandidate(
                url = resolveUrl(doc.baseUri(), src),
                mimeType = getMimeType(src),
                type = "video_element",
                priority = 100
            ))
        }
    }
}

private fun extractFromSourceTags(doc: Document, candidates: MutableList<VideoCandidate>) {
    doc.select("source").forEach { source ->
        source.attr("src").takeIf { it.isNotBlank() }?.let { src ->
            candidates.add(VideoCandidate(
                url = resolveUrl(doc.baseUri(), src),
                mimeType = source.attr("type").takeIf { it.isNotBlank() } ?: getMimeType(src),
                type = "video_source",
                priority = 90
            ))
        }
    }
}

private fun extractFromScripts(doc: Document, candidates: MutableList<VideoCandidate>) {
    val scripts = doc.select("script")
    val allText = StringBuilder(doc.body()?.text() ?: "")
    scripts.forEach { allText.append(it.html()) }

    val patterns = listOf(
        Regex("https?://[^\\s'\"<>]+\\.m3u8[^\\s'\"<>]*", RegexOption.IGNORE_CASE),
        Regex("https?://[^\\s'\"<>]+\\.mpd[^\\s'\"<>]*", RegexOption.IGNORE_CASE),
        Regex("https?://[^\\s'\"<>]+\\.mp4[^\\s'\"<>]*", RegexOption.IGNORE_CASE),
        Regex("https?://[^\\s'\"<>]+\\.webm[^\\s'\"<>]*", RegexOption.IGNORE_CASE),
        Regex("https?://[^\\s'\"<>]+\\.mkv[^\\s'\"<>]*", RegexOption.IGNORE_CASE),
        Regex("https?://[^\\s'\"<>]+\\.(ts|m4s)[^\\s'\"<>]*", RegexOption.IGNORE_CASE),
    )

    patterns.forEach { pattern ->
        pattern.findAll(allText).forEach { match ->
            val url = match.value.trim().trimEnd('"', '\'', '>', '<')
            if (url.isNotBlank() && URLUtil.isValidUrl(url)) {
                candidates.add(VideoCandidate(
                    url = url,
                    mimeType = getMimeType(url),
                    type = "regex_match",
                    priority = when {
                        url.endsWith(".m3u8") -> 50
                        url.endsWith(".mpd") -> 45
                        url.endsWith(".mp4") -> 40
                        url.endsWith(".webm") -> 35
                        else -> 30
                    }
                ))
            }
        }
    }
}

private fun extractYouTube(doc: Document, pageUrl: String, candidates: MutableList<VideoCandidate>) {
    // YouTube embeds
    doc.select("iframe[src*=\"youtube\"], iframe[src*=\"youtu.be\"]").forEach { iframe ->
        val src = iframe.attr("src")
        extractYouTubeId(src)?.let { videoId ->
            candidates.add(VideoCandidate(
                url = "https://youtu.be/$videoId",
                mimeType = "video/youtube",
                type = "youtube_embed",
                priority = 75
            ))
        }
    }

    // YouTube page
    if (pageUrl.contains("youtube.com") || pageUrl.contains("youtu.be")) {
        extractYouTubeId(pageUrl)?.let { videoId ->
            candidates.add(VideoCandidate(
                url = "https://youtu.be/$videoId",
                mimeType = "video/youtube",
                type = "youtube_page",
                priority = 80
            ))
        }
    }
}

private fun extractYouTubeId(url: String): String? {
    val patterns = listOf(
        Regex("youtube\\.com/(?:[^/]+/.+/|(?:v|e(?:mbed)?)/|.*[?&]v=)([^\"&?/\\s]{11})"),
        Regex("youtu\\.be/([^\"&?/\\s]{11})"),
        Regex("youtube\\.com/shorts/([^\"&?/\\s]{11})"),
    )
    patterns.forEach { pattern ->
        pattern.find(url)?.groupValues?.get(1)?.let { return it }
    }
    return null
}

private fun extractVimeo(doc: Document, candidates: MutableList<VideoCandidate>) {
    doc.select("iframe[src*=\"vimeo\"]").forEach { iframe ->
        val src = iframe.attr("src")
        extractVimeoId(src)?.let { videoId ->
            candidates.add(VideoCandidate(
                url = "https://player.vimeo.com/video/$videoId",
                mimeType = "video/vimeo",
                type = "vimeo_embed",
                priority = 70
            ))
        }
    }
}

private fun extractVimeoId(url: String): String? {
    val pattern = Regex("vimeo\\.com/([0-9]+)")
    return pattern.find(url)?.groupValues?.get(1)
}

private fun extractFromMetaTags(doc: Document, candidates: MutableList<VideoCandidate>) {
    // Open Graph video
    doc.select("meta[property^=\"og:video\"]").forEach { meta ->
        meta.attr("content").takeIf { it.isNotBlank() && isVideoUrl(it) }?.let { url ->
            candidates.add(VideoCandidate(
                url = resolveUrl(doc.baseUri(), url),
                mimeType = getMimeType(url),
                type = "og_video",
                priority = 65
            ))
        }
    }

    // Twitter Player
    doc.select("meta[name=\"twitter:player\"]").forEach { meta ->
        meta.attr("content").takeIf { it.isNotBlank() && isVideoUrl(it) }?.let { url ->
            candidates.add(VideoCandidate(
                url = resolveUrl(doc.baseUri(), url),
                mimeType = getMimeType(url),
                type = "twitter_player",
                priority = 60
            ))
        }
    }
}

private fun extractFromJsonLd(doc: Document, candidates: MutableList<VideoCandidate>) {
    doc.select("script[type=\"application/ld+json\"]").forEach { script ->
        try {
            val json = Json.parseToJsonElement(script.html())
            if (json is JsonObject) {
                extractVideoFromJsonLd(json, doc.baseUri(), candidates)
            } else if (json is JsonArray) {
                json.forEach { elem ->
                    if (elem is JsonObject) {
                        extractVideoFromJsonLd(elem, doc.baseUri(), candidates)
                    }
                }
            }
        } catch (e: Exception) {
            // Ignore JSON parse errors
        }
    }
}

private fun extractVideoFromJsonLd(json: JsonObject, baseUrl: String, candidates: MutableList<VideoCandidate>) {
    val type = json["@type"]?.jsonPrimitive?.content
    if (type == "VideoObject" || type == "BroadcastEvent") {
        json["contentUrl"]?.jsonPrimitive?.content?.let { url ->
            candidates.add(VideoCandidate(
                url = resolveUrl(baseUrl, url),
                mimeType = getMimeType(url),
                type = "json_ld",
                priority = 60
            ))
        }
        json["embedUrl"]?.jsonPrimitive?.content?.let { url ->
            candidates.add(VideoCandidate(
                url = resolveUrl(baseUrl, url),
                mimeType = getMimeType(url),
                type = "json_ld_embed",
                priority = 55
            ))
        }
    }

    // Check for @graph array
    json["@graph"]?.let { graph ->
        if (graph is JsonArray) {
            graph.forEach { elem ->
                if (elem is JsonObject) {
                    extractVideoFromJsonLd(elem, baseUrl, candidates)
                }
            }
        }
    }
}

private fun extractThumbnail(doc: Document): String? {
    // Try Open Graph image
    doc.select("meta[property=\"og:image\"]").first()?.attr("content")?.takeIf { it.isNotBlank() }?.let {
        return resolveUrl(doc.baseUri(), it)
    }
    // Try Twitter image
    doc.select("meta[name=\"twitter:image\"]").first()?.attr("content")?.takeIf { it.isNotBlank() }?.let {
        return resolveUrl(doc.baseUri(), it)
    }
    // Try first large image
    doc.select("img[width]:not([width=\"\"]), img[height]:not([height=\"\"])").firstOrNull()?.let { img ->
        val width = img.attr("width").toIntOrNull() ?: 0
        val height = img.attr("height").toIntOrNull() ?: 0
        if (width >= 200 || height >= 200) {
            return resolveUrl(doc.baseUri(), img.attr("src"))
        }
    }
    return null
}

private fun getMimeType(url: String): String {
    return when {
        url.contains(".m3u8") -> "application/vnd.apple.mpegurl"
        url.contains(".mpd") -> "application/dash+xml"
        url.contains(".mp4") -> "video/mp4"
        url.contains(".webm") -> "video/webm"
        url.contains(".mkv") -> "video/x-matroska"
        url.contains(".ts") -> "video/mp2t"
        url.contains(".m4s") -> "video/iso.segment"
        url.contains("youtu.be") || url.contains("youtube") -> "video/youtube"
        url.contains("vimeo") -> "video/vimeo"
        else -> "video/mp4"
    }
}

private fun isVideoUrl(url: String): Boolean {
    return url.matches(Regex(".*\\.(m3u8|mpd|mp4|webm|mkv|ts|m4s)(\\?|$)")) ||
           url.contains("youtu.be") ||
           url.contains("youtube.com/watch") ||
           url.contains("vimeo.com/video")
}

private fun resolveUrl(base: String, relative: String): String {
    return try {
        URL(URL(base), relative).toString()
    } catch (e: Exception) {
        relative
    }
}

object URLUtil {
    fun isValidUrl(url: String): Boolean {
        return try {
            URL(url).toURI()
            true
        } catch (e: Exception) {
            false
        }
    }
}