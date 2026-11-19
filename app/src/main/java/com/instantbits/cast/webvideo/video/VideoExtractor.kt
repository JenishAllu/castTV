package com.instantbits.cast.webvideo.video

import android.webkit.WebView
import com.instantbits.cast.webvideo.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Extracts direct video URLs from web pages.
 * Supports: direct video tags, m3u8 (HLS), mpd (DASH), mp4, YouTube embeds.
 */
class VideoExtractor {

    // JavaScript interface for extracting video URLs from the page
    private const val JS_EXTRACT_VIDEO = """
        (function() {
            var results = {
                videoUrls: [],
                pageTitle: document.title,
                pageUrl: window.location.href
            };

            // 1. Check for <video> elements
            var videos = document.querySelectorAll('video');
            for (var i = 0; i < videos.length; i++) {
                var video = videos[i];
                if (video.currentSrc) {
                    results.videoUrls.push({
                        url: video.currentSrc,
                        type: 'video_element',
                        mimeType: video.type || getMimeType(video.currentSrc)
                    });
                }
                // Check source children
                var sources = video.querySelectorAll('source');
                for (var j = 0; j < sources.length; j++) {
                    if (sources[j].src) {
                        results.videoUrls.push({
                            url: sources[j].src,
                            type: 'video_source',
                            mimeType: sources[j].type || getMimeType(sources[j].src)
                        });
                    }
                }
            }

            // 2. Scan for m3u8, mpd, mp4 in page scripts/text
            var scripts = document.querySelectorAll('script');
            var textContent = document.body ? document.body.innerText : '';
            var allText = textContent;
            for (var i = 0; i < scripts.length; i++) {
                allText += scripts[i].innerText;
            }

            // Regex patterns for stream URLs
            var patterns = [
                /https?:\/\/[^\s'"<>]+\.m3u8[^\s'"<>]*/gi,
                /https?:\/\/[^\s'"<>]+\.mpd[^\s'"<>]*/gi,
                /https?:\/\/[^\s'"<>]+\.mp4[^\s'"<>]*/gi,
                /https?:\/\/[^\s'"<>]+\.webm[^\s'"<>]*/gi,
                /https?:\/\/[^\s'"<>]+\.mkv[^\s'"<>]*/gi
            ];

            for (var p = 0; p < patterns.length; p++) {
                var matches = allText.match(patterns[p]);
                if (matches) {
                    for (var m = 0; m < matches.length; m++) {
                        var url = matches[m].replace(/['"<>]$/, '');
                        results.videoUrls.push({
                            url: url,
                            type: 'regex_match',
                            mimeType: getMimeType(url)
                        });
                    }
                }
            }

            // 3. YouTube embed detection
            var ytEmbeds = document.querySelectorAll('iframe[src*="youtube"], iframe[src*="youtu.be"]');
            for (var i = 0; i < ytEmbeds.length; i++) {
                var src = ytEmbeds[i].src;
                var videoId = extractYouTubeId(src);
                if (videoId) {
                    // Convert to youtu.be format for Default Receiver
                    results.videoUrls.push({
                        url: 'https://youtu.be/' + videoId,
                        type: 'youtube_embed',
                        mimeType: 'video/youtube'
                    });
                }
            }

            // 4. YouTube watch page detection
            if (window.location.hostname.includes('youtube.com') ||
                window.location.hostname.includes('youtu.be')) {
                var videoId = extractYouTubeId(window.location.href);
                if (videoId) {
                    results.videoUrls.push({
                        url: 'https://youtu.be/' + videoId,
                        type: 'youtube_page',
                        mimeType: 'video/youtube'
                    });
                }
            }

            // 5. Vimeo embed detection
            var vimeoEmbeds = document.querySelectorAll('iframe[src*="vimeo"]');
            for (var i = 0; i < vimeoEmbeds.length; i++) {
                var src = vimeoEmbeds[i].src;
                var videoId = extractVimeoId(src);
                if (videoId) {
                    results.videoUrls.push({
                        url: 'https://player.vimeo.com/video/' + videoId,
                        type: 'vimeo_embed',
                        mimeType: 'video/vimeo'
                    });
                }
            }

            // 6. Check for video in meta tags (Open Graph, Twitter Card)
            var metaTags = document.querySelectorAll('meta[property^="og:video"], meta[name^="twitter:player"]');
            for (var i = 0; i < metaTags.length; i++) {
                var content = metaTags[i].getAttribute('content');
                if (content && isVideoUrl(content)) {
                    results.videoUrls.push({
                        url: content,
                        type: 'meta_tag',
                        mimeType: getMimeType(content)
                    });
                }
            }

            // Deduplicate
            var seen = {};
            results.videoUrls = results.videoUrls.filter(function(item) {
                if (seen[item.url]) return false;
                seen[item.url] = true;
                return true;
            });

            return JSON.stringify(results);

            function getMimeType(url) {
                if (url.includes('.m3u8')) return 'application/vnd.apple.mpegurl';
                if (url.includes('.mpd')) return 'application/dash+xml';
                if (url.includes('.mp4')) return 'video/mp4';
                if (url.includes('.webm')) return 'video/webm';
                if (url.includes('.mkv')) return 'video/x-matroska';
                if (url.includes('youtu.be') || url.includes('youtube')) return 'video/youtube';
                if (url.includes('vimeo')) return 'video/vimeo';
                return 'video/mp4';
            }

            function extractYouTubeId(url) {
                var match = url.match(/(?:youtube\.com\/(?:[^\/]+\/.+\/|(?:v|e(?:mbed)?)\/|.*[?&]v=)|youtu\.be\/)([^"&?\/\s]{11})/);
                return match ? match[1] : null;
            }

            function extractVimeoId(url) {
                var match = url.match(/(?:vimeo\.com\/)([0-9]+)/);
                return match ? match[1] : null;
            }

            function isVideoUrl(url) {
                return url.match(/\.(m3u8|mpd|mp4|webm|mkv)(\?|$)/i) ||
                       url.includes('youtu.be') ||
                       url.includes('youtube.com/watch') ||
                       url.includes('vimeo.com/video');
            }
        })()
    """

    /**
     * Extract video URLs from the current WebView page.
     * Returns a VideoExtractionResult with the best candidate URL.
     */
    suspend fun extractVideoUrl(webView: WebView): VideoExtractionResult {
        return withContext(Dispatchers.IO) {
            try {
                val jsonResult = webView.evaluateJavascriptSync(JS_EXTRACT_VIDEO)
                parseExtractionResult(jsonResult)
            } catch (e: Exception) {
                Timber.e(e, "Failed to extract video URL")
                VideoExtractionResult(success = false, error = "Extraction failed: ${e.message}")
            }
        }
    }

    /**
     * Parse the JSON result from JavaScript extraction.
     */
    private fun parseExtractionResult(json: String): VideoExtractionResult {
        try {
            // Simple JSON parsing without external dependencies
            val videoUrls = mutableListOf<VideoCandidate>()

            // Extract videoUrls array from JSON
            val urlsJson = extractJsonArray(json, "videoUrls")
            if (urlsJson.isNotEmpty()) {
                parseVideoCandidates(urlsJson, videoUrls)
            }

            val pageTitle = extractJsonString(json, "pageTitle") ?: "Unknown"
            val pageUrl = extractJsonString(json, "pageUrl") ?: ""

            if (videoUrls.isEmpty()) {
                return VideoExtractionResult(
                    success = false,
                    error = "No video found on this page",
                    pageTitle = pageTitle,
                    pageUrl = pageUrl
                )
            }

            // Sort by priority: direct video > hls/dash > youtube > vimeo > others
            val bestCandidate = videoUrls.maxByOrNull { it.priority } ?: videoUrls[0]

            return VideoExtractionResult(
                success = true,
                videoUrl = bestCandidate.url,
                mimeType = bestCandidate.mimeType,
                type = bestCandidate.type,
                pageTitle = pageTitle,
                pageUrl = pageUrl,
                allCandidates = videoUrls
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to parse extraction result")
            VideoExtractionResult(success = false, error = "Parse failed: ${e.message}")
        }
    }

    private fun extractJsonArray(json: String, key: String): String {
        val pattern = "\"$key\"\\s*:\\s*\\[([^\\]]*)\\]".toRegex()
        val match = pattern.find(json)
        return match?.groupValues?.get(1) ?: ""
    }

    private fun extractJsonString(json: String, key: String): String? {
        val pattern = "\"$key\"\\s*:\\s*\"([^\"]*)\"".toRegex()
        val match = pattern.find(json)
        return match?.groupValues?.get(1)
    }

    private fun parseVideoCandidates(urlsJson: String, output: MutableList<VideoCandidate>) {
        // Split by object boundaries
        val objects = urlsJson.split("},").map { it.trim() }.filter { it.isNotEmpty() }
        for (obj in objects) {
            val cleaned = obj.replace("}", "").replace("{", "")
            val url = extractJsonString(cleaned, "url") ?: ""
            val type = extractJsonString(cleaned, "type") ?: ""
            val mimeType = extractJsonString(cleaned, "mimeType") ?: "video/mp4"

            if (url.isNotEmpty()) {
                output.add(VideoCandidate(url, type, mimeType))
            }
        }
    }

    /**
     * Get priority for sorting video candidates (higher = better).
     * Direct video elements > HLS/DASH > YouTube > Vimeo > regex matches
     */
    private fun VideoCandidate.getPriority(): Int {
        return when (type) {
            "video_element" -> 100
            "video_source" -> 90
            "youtube_page" -> 80
            "youtube_embed" -> 75
            "vimeo_embed" -> 70
            "meta_tag" -> 60
            "regex_match" -> when {
                mimeType == "application/vnd.apple.mpegurl" -> 50  // HLS
                mimeType == "application/dash+xml" -> 45         // DASH
                mimeType == "video/mp4" -> 40
                else -> 30
            }
            else -> 10
        }
    }

    data class VideoCandidate(
        val url: String,
        val type: String,
        val mimeType: String
    ) {
        val priority: Int
            get() = getPriority()
    }
}

/**
 * Result of video URL extraction.
 */
data class VideoExtractionResult(
    val success: Boolean,
    val videoUrl: String? = null,
    val mimeType: String? = null,
    val type: String? = null,
    val pageTitle: String = "",
    val pageUrl: String = "",
    val allCandidates: List<VideoExtractor.VideoCandidate> = emptyList(),
    val error: String? = null
) {
    val hasVideo: Boolean
        get() = success && videoUrl != null
}

/**
 * Extension function to evaluate JavaScript synchronously (for coroutine usage).
 */
private fun WebView.evaluateJavascriptSync(script: String): String {
    return kotlinx.coroutines.tasks.await(
        kotlinx.coroutines.tasks.asCoroutine(this::evaluateJavascript, script)
    )
}