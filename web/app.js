/**
 * Web Video Caster - Web-based Chromecast Sender App
 * Free, ad-free, open-source web app to cast videos to Chromecast/Google TV
 */

// ============================================
// CONFIGURATION
// ============================================
const CONFIG = {
    // Default Media Receiver (free, no $5 console fee)
    defaultReceiverAppId: 'CC1AD845',
    // Video extraction patterns
    videoPatterns: [
        /\.m3u8(\?|$)/i,
        /\.mpd(\?|$)/i,
        /\.mp4(\?|$)/i,
        /\.webm(\?|$)/i,
        /\.mkv(\?|$)/i,
        /\.mov(\?|$)/i,
        /\.avi(\?|$)/i,
    ],
    // YouTube patterns
    youtubeWatchPattern: /youtube\.com\/watch\?v=([^&]+)/i,
    youtubeShortsPattern: /youtube\.com\/shorts\/([^?&]+)/i,
    youtuBePattern: /youtu\.be\/([^?&]+)/i,
    // Vimeo patterns
    vimeoPattern: /vimeo\.com\/(\d+)/i,
};

// Quick access sites for start page
const QUICK_SITES = [
    { name: 'YouTube', url: 'https://www.youtube.com', icon: '📺', color: '#FF0000' },
    { name: 'Vimeo', url: 'https://vimeo.com', icon: '📹', color: '#1AB7EA' },
    { name: 'Twitch', url: 'https://www.twitch.tv', icon: '🎮', color: '#9146FF' },
    { name: 'Reddit', url: 'https://www.reddit.com', icon: '🔴', color: '#FF4500' },
    { name: 'Twitter/X', url: 'https://x.com', icon: '🐦', color: '#000000' },
    { name: 'Facebook', url: 'https://www.facebook.com', icon: '📘', color: '#1877F2' },
    { name: 'Instagram', url: 'https://www.instagram.com', icon: '📷', color: '#E4405F' },
    { name: 'TikTok', url: 'https://www.tiktok.com', icon: '🎵', color: '#000000' },
    { name: 'CNN', url: 'https://www.cnn.com', icon: '📰', color: '#CC0000' },
    { name: 'BBC', url: 'https://www.bbc.com', icon: '🌐', color: '#BB1919' },
    { name: 'Google', url: 'https://www.google.com', icon: '🔍', color: '#4285F4' },
    { name: 'Bing Video', url: 'https://www.bing.com/videos', icon: '🎬', color: '#00A6ED' },
];

// ============================================
// STATE MANAGEMENT
// ============================================
const state = {
    castContext: null,
    castSession: null,
    currentMedia: null,
    mediaController: null,
    isCasting: false,
    discoveredVideo: null,
    webviewLoaded: false,
    isExtracting: false,
};

// ============================================
// DOM ELEMENTS
// ============================================
const elements = {
    castButton: document.getElementById('castButton'),
    urlInput: document.getElementById('urlInput'),
    goButton: document.getElementById('goButton'),
    webview: document.getElementById('webview'),
    webviewContainer: document.getElementById('webviewContainer'),
    videoFoundCard: document.getElementById('videoFoundCard'),
    videoThumbnail: document.getElementById('videoThumbnail'),
    videoTitle: document.getElementById('videoTitle'),
    videoSource: document.getElementById('videoSource'),
    playOnTvButton: document.getElementById('playOnTvButton'),
    castStatusBar: document.getElementById('castStatusBar'),
    castDeviceName: document.getElementById('castDeviceName'),
    castStatusText: document.getElementById('castStatusText'),
    castStatusIcon: document.getElementById('castStatusIcon'),
    castPlayPause: document.getElementById('castPlayPause'),
    castStop: document.getElementById('castStop'),
    castSeekBar: document.getElementById('castSeekBar'),
    castProgress: document.getElementById('castProgress'),
    snackbar: document.getElementById('snackbar'),
    snackbarLabel: document.getElementById('snackbarLabel'),
    snackbarAction: document.getElementById('snackbarAction'),
    // Start page elements
    startPage: document.getElementById('startPage'),
    startSearchInput: document.getElementById('startSearchInput'),
    startSearchButton: document.getElementById('startSearchButton'),
    quickSitesGrid: document.getElementById('quickSitesGrid'),
};

// ============================================
// UTILITY FUNCTIONS
// ============================================
function showSnackbar(message, actionLabel = null, actionCallback = null, duration = 5000) {
    elements.snackbarLabel.textContent = message;
    if (actionLabel && actionCallback) {
        elements.snackbarAction.textContent = actionLabel;
        elements.snackbarAction.onclick = () => {
            actionCallback();
            hideSnackbar();
        };
        elements.snackbarAction.style.display = 'inline-block';
    } else {
        elements.snackbarAction.style.display = 'none';
    }
    elements.snackbar.classList.add('mdc-snackbar--open');
    elements.snackbar.setAttribute('aria-hidden', 'false');
    setTimeout(hideSnackbar, duration);
}

function hideSnackbar() {
    elements.snackbar.classList.remove('mdc-snackbar--open');
    elements.snackbar.setAttribute('aria-hidden', 'true');
}

function normalizeUrl(input) {
    input = input.trim();
    // Handle youtu.be links
    if (CONFIG.youtuBePattern.test(input)) {
        const match = input.match(CONFIG.youtuBePattern);
        return `https://www.youtube.com/watch?v=${match[1]}`;
    }
    // Add protocol if missing
    if (!/^https?:\/\//i.test(input)) {
        // Check if it looks like a domain/URL
        if (input.includes('.') && !input.includes(' ')) {
            input = 'https://' + input;
        } else {
            // Treat as search query
            input = `https://www.google.com/search?q=${encodeURIComponent(input)}`;
        }
    }
    return input;
}

function extractDomain(url) {
    try {
        return new URL(url).hostname.replace('www.', '');
    } catch {
        return 'Unknown';
    }
}

// ============================================
// VIDEO EXTRACTION (injected into iframe)
// ============================================
const EXTRACTION_SCRIPT = `
(function() {
    function extractVideos() {
        const results = [];

        // 1. Direct video elements
        document.querySelectorAll('video').forEach(video => {
            if (video.currentSrc) {
                results.push({
                    url: video.currentSrc,
                    type: 'video',
                    title: video.title || document.title,
                    poster: video.poster
                });
            }
            video.querySelectorAll('source').forEach(source => {
                if (source.src) {
                    results.push({
                        url: source.src,
                        type: source.type || 'video',
                        title: video.title || document.title,
                        poster: video.poster
                    });
                }
            });
        });

        // 2. Regex scan for video URLs in scripts and text
        const videoRegex = /(https?:\\/\\/[^\\s"'<>]+\\.(?:m3u8|mpd|mp4|webm|mkv|mov|avi)(?:\\?[^\\s"'<>]*)?)/gi;
        const allText = document.documentElement.outerHTML;
        let match;
        while ((match = videoRegex.exec(allText)) !== null) {
            results.push({
                url: match[1],
                type: 'regex',
                title: document.title
            });
        }

        // 3. YouTube detection
        const ytWatchMatch = window.location.href.match(/youtube\\.com\\/watch\\?v=([^&]+)/i);
        const ytShortsMatch = window.location.href.match(/youtube\\.com\\/shorts\\/([^?&]+)/i);
        if (ytWatchMatch) {
            results.push({
                url: 'https://youtu.be/' + ytWatchMatch[1],
                type: 'youtube',
                title: document.title
            });
        } else if (ytShortsMatch) {
            results.push({
                url: 'https://youtu.be/' + ytShortsMatch[1],
                type: 'youtube',
                title: document.title
            });
        }

        // 4. Vimeo detection
        const vimeoMatch = window.location.href.match(/vimeo\\.com\\/(\\d+)/i);
        if (vimeoMatch) {
            results.push({
                url: 'https://player.vimeo.com/video/' + vimeoMatch[1],
                type: 'vimeo',
                title: document.title
            });
        }

        // 5. Meta tags (Open Graph, Twitter Card)
        const metaSelectors = [
            'meta[property="og:video"]',
            'meta[property="og:video:url"]',
            'meta[property="og:video:secure_url"]',
            'meta[name="twitter:player"]',
            'meta[name="twitter:player:stream"]',
            'meta[property="og:video:type"]'
        ];
        metaSelectors.forEach(selector => {
            document.querySelectorAll(selector).forEach(meta => {
                if (meta.content) {
                    results.push({
                        url: meta.content,
                        type: 'meta',
                        title: document.title
                    });
                }
            });
        });

        // 6. JSON-LD structured data
        document.querySelectorAll('script[type="application/ld+json"]').forEach(script => {
            try {
                const data = JSON.parse(script.textContent);
                const items = Array.isArray(data) ? data : [data];
                items.forEach(item => {
                    if (item['@type'] === 'VideoObject' && item.contentUrl) {
                        results.push({
                            url: item.contentUrl,
                            type: 'json-ld',
                            title: item.name || document.title,
                            poster: item.thumbnailUrl
                        });
                    }
                    if (item['@type'] === 'BroadcastEvent' && item.video && item.video.contentUrl) {
                        results.push({
                            url: item.video.contentUrl,
                            type: 'json-ld',
                            title: item.name || document.title,
                            poster: item.video.thumbnailUrl
                        });
                    }
                });
            } catch (e) {}
        });

        // Deduplicate
        const seen = new Set();
        return results.filter(r => {
            if (seen.has(r.url)) return false;
            seen.add(r.url);
            return true;
        });
    }

    return extractVideos();
}
`;

// ============================================
// CAST SDK INITIALIZATION
// ============================================
function initializeCast() {
    if (!window.chrome || !window.chrome.cast || !window.chrome.cast.isAvailable) {
        console.warn('Cast SDK not available');
        showSnackbar('Cast not available. Open in Chrome/Edge browser.', 'Dismiss');
        elements.castButton.setAttribute('aria-disabled', 'true');
        elements.castButton.style.opacity = '0.5';
        return;
    }

    const sessionRequest = new chrome.cast.SessionRequest(CONFIG.defaultReceiverAppId);
    const apiConfig = new chrome.cast.ApiConfig(
        sessionRequest,
        sessionListener,
        receiverListener,
        // Auto-join policy
        chrome.cast.AutoJoinPolicy.TAB_AND_ORIGIN_SCOPED
    );

    chrome.cast.initialize(apiConfig, onInitSuccess, onInitError);
}

function onInitSuccess() {
    console.log('Cast SDK initialized');
    elements.castButton.removeAttribute('aria-disabled');
    elements.castButton.style.opacity = '1';
}

function onInitError(error) {
    console.error('Cast SDK init error:', error);
    showSnackbar('Failed to initialize Cast. Check browser support.', 'Dismiss');
    elements.castButton.setAttribute('aria-disabled', 'true');
    elements.castButton.style.opacity = '0.5';
}

function sessionListener(session) {
    console.log('Session created:', session.sessionId);
    state.castSession = session;
    state.isCasting = true;
    updateCastUI();
    setupMediaController();
}

function receiverListener(availability) {
    console.log('Receiver availability:', availability);
    if (availability === chrome.cast.ReceiverAvailability.AVAILABLE) {
        elements.castButton.removeAttribute('aria-disabled');
        elements.castButton.style.opacity = '1';
        elements.castButton.classList.add('cast-available');
    } else {
        elements.castButton.setAttribute('aria-disabled', 'true');
        elements.castButton.style.opacity = '0.5';
        elements.castButton.classList.remove('cast-available');
    }
}

// ============================================
// CAST CONTROLS
// ============================================
function launchCast() {
    if (!state.castContext) return;
    state.castContext.requestSession()
        .then(sessionListener)
        .catch(err => {
            console.error('Cast launch error:', err);
            if (err.code !== chrome.cast.ErrorCode.CANCEL) {
                showSnackbar('Failed to connect to Cast device', 'Retry', launchCast);
            }
        });
}

function stopCasting() {
    if (state.castSession) {
        state.castSession.stop(stopSuccess, stopError);
    }
}

function stopSuccess() {
    console.log('Cast stopped');
    state.castSession = null;
    state.isCasting = false;
    state.currentMedia = null;
    state.mediaController = null;
    updateCastUI();
    showSnackbar('Stopped casting');
}

function stopError(error) {
    console.error('Stop error:', error);
    showSnackbar('Failed to stop casting');
}

function setupMediaController() {
    if (!state.castSession) return;
    state.mediaController = state.castSession.getMediaSession();
    if (state.mediaController) {
        addMediaListeners();
        updateCastProgress();
    }
}

function addMediaListeners() {
    if (!state.mediaController) return;

    state.mediaController.addUpdateListener(mediaUpdateListener);

    // Listen for media status changes
    const originalOnStatus = state.mediaController.onstatuschange;
    state.mediaController.onstatuschange = (e) => {
        updateCastUI();
        if (originalOnStatus) originalOnStatus.call(state.mediaController, e);
    };
}

function mediaUpdateListener(isAlive) {
    if (!isAlive) {
        console.log('Media session ended');
        state.currentMedia = null;
        updateCastUI();
    } else {
        updateCastProgress();
    }
}

function loadMedia(videoInfo) {
    if (!state.castSession) {
        showSnackbar('Not connected to Cast device');
        return;
    }

    // Validate and fix URL for Default Receiver
    const fixedVideoInfo = prepareVideoForCasting(videoInfo);
    if (!fixedVideoInfo) {
        showSnackbar('Cannot cast this video format. Try a direct .mp4/.m3u8/.mpd link or YouTube.', 'Dismiss');
        return;
    }

    console.log('Casting:', fixedVideoInfo);

    const mediaInfo = new chrome.cast.media.MediaInfo(fixedVideoInfo.url, fixedVideoInfo.mimeType);
    mediaInfo.metadata = new chrome.cast.media.GenericMediaMetadata();
    mediaInfo.metadata.title = fixedVideoInfo.title || 'Web Video';
    if (fixedVideoInfo.poster) {
        mediaInfo.metadata.images = [{ url: fixedVideoInfo.poster }];
    }

    // Add custom data for receiver
    mediaInfo.customData = {
        sourceUrl: videoInfo.url,
        originalType: videoInfo.type
    };

    const request = new chrome.cast.media.LoadRequest(mediaInfo);
    request.autoplay = true;
    request.currentTime = 0;

    state.castSession.loadMedia(request)
        .then(media => {
            console.log('Media loaded:', media.mediaSessionId);
            state.currentMedia = media;
            state.mediaController = media;
            addMediaListeners();
            updateCastUI();
            updateCastProgress();
            hideVideoCard();
            showSnackbar('Now playing on TV');
        })
        .catch(err => {
            console.error('Load media error:', err.code, err.description, err.details);
            let msg = 'Failed to cast: ';
            switch (err.code) {
                case chrome.cast.ErrorCode.INVALID_PARAMETER:
                    msg += 'Invalid video URL or format';
                    break;
                case chrome.cast.ErrorCode.LOAD_FAILED:
                    msg += 'Video failed to load (check CORS/format)';
                    break;
                case chrome.cast.ErrorCode.TIMEOUT:
                    msg += 'Connection timeout';
                    break;
                default:
                    msg += err.description || 'Unknown error';
            }
            showSnackbar(msg, 'Retry', () => loadMedia(videoInfo));
        });
}

function prepareVideoForCasting(videoInfo) {
    const url = videoInfo.url;
    const type = videoInfo.type;

    // YouTube: Must use youtu.be format for Default Receiver
    if (type === 'youtube' || url.includes('youtube.com') || url.includes('youtu.be')) {
        let videoId = null;
        const patterns = [
            /youtu\.be\/([^?&]+)/,
            /youtube\.com\/watch\?v=([^&]+)/,
            /youtube\.com\/shorts\/([^?&]+)/,
            /youtube\.com\/embed\/([^?&]+)/
        ];
        for (const pattern of patterns) {
            const match = url.match(pattern);
            if (match) { videoId = match[1]; break; }
        }
        if (videoId) {
            return {
                ...videoInfo,
                url: `https://www.youtube.com/embed/${videoId}?autoplay=1&enablejsapi=1`,
                mimeType: 'text/html',
                type: 'youtube-embed'
            };
        }
    }

    // Vimeo: Use player.vimeo.com embed
    if (type === 'vimeo' || url.includes('vimeo.com')) {
        let videoId = null;
        const match = url.match(/vimeo\.com\/(\d+)/);
        if (match) videoId = match[1];
        else if (url.includes('player.vimeo.com/video/')) {
            videoId = url.split('player.vimeo.com/video/')[1].split('?')[0].split('/')[0];
        }
        if (videoId) {
            return {
                ...videoInfo,
                url: `https://player.vimeo.com/video/${videoId}?autoplay=1&api=1`,
                mimeType: 'text/html',
                type: 'vimeo-embed'
            };
        }
    }

    // Direct video formats supported by Default Receiver
    const supportedVideoTypes = {
        'application/vnd.apple.mpegurl': ['.m3u8', 'm3u8'],
        'application/dash+xml': ['.mpd', 'mpd'],
        'video/mp4': ['.mp4', 'mp4'],
        'video/webm': ['.webm', 'webm'],
    };

    // Check if URL has supported extension
    for (const [mimeType, extensions] of Object.entries(supportedVideoTypes)) {
        for (const ext of extensions) {
            if (url.includes(ext)) {
                return {
                    ...videoInfo,
                    url: url,
                    mimeType: mimeType
                };
            }
        }
    }

    // Try to infer from mimeType if provided
    if (videoInfo.mimeType && supportedVideoTypes[videoInfo.mimeType]) {
        return { ...videoInfo, mimeType: videoInfo.mimeType };
    }

    // Unknown format - warn but try anyway
    console.warn('Unknown video format, attempting to cast:', url);
    return {
        ...videoInfo,
        mimeType: videoInfo.mimeType || 'video/mp4'
    };
}

function togglePlayPause() {
    if (!state.mediaController) return;
    if (state.currentMedia && state.currentMedia.playerState === 'PLAYING') {
        state.mediaController.pause();
        elements.castPlayPause.textContent = 'play_arrow';
    } else {
        state.mediaController.play();
        elements.castPlayPause.textContent = 'pause';
    }
}

function seekTo(percent) {
    if (!state.mediaController || !state.currentMedia) return;
    const duration = state.currentMedia.media.duration;
    if (duration) {
        state.currentMedia.currentTime = (percent / 100) * duration;
        state.mediaController.seek(new chrome.cast.media.SeekRequest(state.currentMedia.currentTime));
    }
}

// ============================================
// UI UPDATES
// ============================================
function updateCastUI() {
    const isConnected = !!state.castSession;
    const isPlaying = state.currentMedia && state.currentMedia.playerState === 'PLAYING';

    elements.castButton.textContent = isConnected ? 'cast_connected' : 'cast';
    elements.castButton.classList.toggle('casting', isConnected);

    if (isConnected) {
        elements.castStatusBar.hidden = false;
        elements.webviewContainer.style.marginBottom = '72px';
        elements.castDeviceName.textContent = state.castSession.receiver.friendlyName || 'Chromecast';
        elements.castPlayPause.textContent = isPlaying ? 'pause' : 'play_arrow';
        elements.castProgress.hidden = false;
    } else {
        elements.castStatusBar.hidden = true;
        elements.webviewContainer.style.marginBottom = '0';
        elements.castProgress.hidden = true;
    }
}

function updateCastProgress() {
    if (!state.currentMedia || !state.currentMedia.media || !state.currentMedia.media.duration) {
        return;
    }
    const percent = (state.currentMedia.currentTime / state.currentMedia.media.duration) * 100;
    elements.castSeekBar.value = percent;
}

function showVideoCard(videoInfo) {
    state.discoveredVideo = videoInfo;
    elements.videoTitle.textContent = videoInfo.title || 'Video Found';
    elements.videoSource.textContent = extractDomain(videoInfo.url);
    if (videoInfo.poster) {
        elements.videoThumbnail.src = videoInfo.poster;
        elements.videoThumbnail.hidden = false;
    } else {
        elements.videoThumbnail.hidden = true;
    }
    elements.videoFoundCard.hidden = false;
}

function hideVideoCard() {
    elements.videoFoundCard.hidden = true;
    state.discoveredVideo = null;
}

// ============================================
// VIDEO EXTRACTION
// ============================================
async function extractVideoFromPage() {
    if (state.isExtracting) return;

    try {
        state.isExtracting = true;
        elements.webviewContainer.classList.add('loading');

        // Wait for iframe to load
        await new Promise(resolve => {
            if (elements.webviewLoaded) {
                resolve();
            } else {
                elements.webview.onload = resolve;
            }
        });

        // Execute extraction script in iframe
        const results = await executeInIframe(EXTRACTION_SCRIPT);

        if (results && results.length > 0) {
            // Prioritize by type
            const priority = { 'video': 1, 'youtube': 2, 'vimeo': 3, 'json-ld': 4, 'meta': 5, 'regex': 6 };
            results.sort((a, b) => (priority[a.type] || 99) - (priority[b.type] || 99));

            const bestVideo = results[0];
            console.log('Found videos:', results);
            showVideoCard(bestVideo);
        } else {
            console.log('No videos found on page');
            hideVideoCard();
        }
    } catch (error) {
        console.error('Extraction error:', error);
    } finally {
        state.isExtracting = false;
        elements.webviewContainer.classList.remove('loading');
    }
}

function executeInIframe(script) {
    return new Promise((resolve, reject) => {
        try {
            // Post message to iframe for extraction
            const channel = new MessageChannel();
            channel.port1.onmessage = (event) => {
                resolve(event.data);
            };

            elements.webview.contentWindow.postMessage({
                type: 'EXTRACT_VIDEO',
                script: script
            }, '*', [channel.port2]);

            // Timeout fallback
            setTimeout(() => reject(new Error('Extraction timeout')), 10000);
        } catch (e) {
            // Fallback: try direct contentWindow access (same-origin only)
            try {
                const result = elements.webview.contentWindow.eval(script);
                resolve(result);
            } catch (err) {
                reject(err);
            }
        }
    });
}

// Alternative: Direct contentScript injection for same-origin pages
async function extractVideoDirect() {
    if (state.isExtracting) return;

    try {
        state.isExtracting = true;
        elements.webviewContainer.classList.add('loading');

        // We can only directly access same-origin iframes
        // For cross-origin, we need a different approach
        const isSameOrigin = isIframeSameOrigin();

        if (isSameOrigin) {
            const results = elements.webview.contentWindow.eval(EXTRACTION_SCRIPT);
            processExtractionResults(results);
        } else {
            // Cross-origin: Try to detect from URL patterns
            await detectVideoFromUrl();
        }
    } catch (error) {
        console.error('Direct extraction error:', error);
        await detectVideoFromUrl();
    } finally {
        state.isExtracting = false;
        elements.webviewContainer.classList.remove('loading');
    }
}

function isIframeSameOrigin() {
    try {
        return elements.webview.contentWindow.location.origin === window.location.origin;
    } catch {
        return false;
    }
}

function processExtractionResults(results) {
    if (results && results.length > 0) {
        const priority = { 'video': 1, 'youtube': 2, 'vimeo': 3, 'json-ld': 4, 'meta': 5, 'regex': 6 };
        results.sort((a, b) => (priority[a.type] || 99) - (priority[b.type] || 99));
        showVideoCard(results[0]);
    } else {
        hideVideoCard();
    }
}

async function detectVideoFromUrl() {
    const url = elements.webview.src || elements.urlInput.value;
    if (!url) return;

    // Check for direct video URLs
    for (const pattern of CONFIG.videoPatterns) {
        if (pattern.test(url)) {
            showVideoCard({
                url: url,
                type: 'direct',
                title: 'Direct Video Link',
                mimeType: getMimeType(url)
            });
            return;
        }
    }

    // YouTube
    const ytMatch = url.match(CONFIG.youtubeWatchPattern) || url.match(CONFIG.youtubeShortsPattern) || url.match(CONFIG.youtuBePattern);
    if (ytMatch) {
        const videoId = ytMatch[1];
        showVideoCard({
            url: `https://youtu.be/${videoId}`,
            type: 'youtube',
            title: 'YouTube Video',
            mimeType: 'video/mp4'
        });
        return;
    }

    // Vimeo
    const vimeoMatch = url.match(CONFIG.vimeoPattern);
    if (vimeoMatch) {
        showVideoCard({
            url: `https://player.vimeo.com/video/${vimeoMatch[1]}`,
            type: 'vimeo',
            title: 'Vimeo Video',
            mimeType: 'video/mp4'
        });
        return;
    }

    // No video detected - let user browse
    hideVideoCard();
}

function getMimeType(url) {
    if (url.includes('.m3u8')) return 'application/vnd.apple.mpegurl';
    if (url.includes('.mpd')) return 'application/dash+xml';
    if (url.includes('.webm')) return 'video/webm';
    if (url.includes('.mp4')) return 'video/mp4';
    return 'video/mp4';
}

// ============================================
// NAVIGATION
// ============================================
function navigateToUrl(url) {
    const normalized = normalizeUrl(url);
    elements.urlInput.value = normalized;
    elements.webview.src = normalized;
    elements.webviewLoaded = false;
    hideVideoCard();
    elements.webviewContainer.classList.add('loading');

    // Hide start page, show webview
    elements.startPage.hidden = true;
    elements.webviewContainer.classList.add('visible');
}

function handleUrlSubmit() {
    const url = elements.urlInput.value.trim();
    if (url) {
        navigateToUrl(url);
    }
}

function handleStartSearch() {
    const input = elements.startSearchInput.value.trim();
    if (input) {
        navigateToUrl(input);
        elements.startSearchInput.value = '';
    }
}

function showStartPage() {
    elements.startPage.hidden = false;
    elements.webviewContainer.classList.remove('visible');
    elements.webview.src = 'about:blank';
    hideVideoCard();
}

function renderQuickSites() {
    const grid = elements.quickSitesGrid;
    if (!grid) return;

    grid.innerHTML = QUICK_SITES.map(site => `
        <a href="${site.url}" class="quick-site-card" data-url="${site.url}" tabindex="0" role="button" aria-label="Open ${site.name}">
            <div class="quick-site-icon" style="background-color: ${site.color}20; color: ${site.color};">
                ${site.icon}
            </div>
            <span class="quick-site-name">${site.name}</span>
        </a>
    `).join('');

    // Add click handlers
    grid.querySelectorAll('.quick-site-card').forEach(card => {
        card.addEventListener('click', (e) => {
            e.preventDefault();
            navigateToUrl(card.dataset.url);
        });
        card.addEventListener('keydown', (e) => {
            if (e.key === 'Enter' || e.key === ' ') {
                e.preventDefault();
                navigateToUrl(card.dataset.url);
            }
        });
    });
}

// ============================================
// EVENT LISTENERS
// ============================================
function setupEventListeners() {
    // Cast button
    elements.castButton.addEventListener('click', () => {
        if (state.castSession) {
            stopCasting();
        } else {
            launchCast();
        }
    });

    // URL input (top bar)
    elements.urlInput.addEventListener('keydown', (e) => {
        if (e.key === 'Enter') {
            handleUrlSubmit();
        }
    });

    elements.goButton.addEventListener('click', handleUrlSubmit);

    // Start page search
    elements.startSearchInput.addEventListener('keydown', (e) => {
        if (e.key === 'Enter') {
            handleStartSearch();
        }
    });

    elements.startSearchButton.addEventListener('click', handleStartSearch);

    // Play on TV button
    elements.playOnTvButton.addEventListener('click', () => {
        if (state.discoveredVideo) {
            loadMedia(state.discoveredVideo);
        }
    });

    // Cast controls
    elements.castPlayPause.addEventListener('click', togglePlayPause);
    elements.castStop.addEventListener('click', stopCasting);
    elements.castSeekBar.addEventListener('change', (e) => {
        seekTo(parseFloat(e.target.value));
    });

    // Webview load
    elements.webview.addEventListener('load', () => {
        elements.webviewLoaded = true;
        elements.webviewContainer.classList.remove('loading');
        // Delay extraction to let page scripts run
        setTimeout(extractVideoDirect, 2000);
    });

    // Webview error
    elements.webview.addEventListener('error', () => {
        elements.webviewContainer.classList.remove('loading');
        showSnackbar('Failed to load page');
    });

    // Snackbar dismiss
    elements.snackbar.querySelector('.mdc-snackbar__dismiss').addEventListener('click', hideSnackbar);

    // Handle hash changes for SPA support
    window.addEventListener('hashchange', () => {
        if (elements.webview.contentWindow) {
            try {
                elements.webview.contentWindow.location.reload();
            } catch {}
        }
    });
}

// ============================================
// INITIALIZATION
// ============================================
function init() {
    console.log('Web Video Caster initializing...');

    // Initialize Material Components
    if (window.mdc) {
        // Auto-init MDC components
        mdc.autoInit();
    }

    // Setup event listeners
    setupEventListeners();

    // Render quick sites on start page
    renderQuickSites();

    // Initialize Cast SDK (wait for SDK to load)
    if (window.chrome && window.chrome.cast && window.chrome.cast.isAvailable) {
        initializeCast();
    } else {
        // Wait for Cast SDK
        window.__onGCastApiAvailable = (loaded, error) => {
            if (loaded) {
                initializeCast();
            } else {
                console.error('Cast SDK failed to load:', error);
                showSnackbar('Cast SDK unavailable. Use Chrome/Edge.', 'Dismiss');
                elements.castButton.setAttribute('aria-disabled', 'true');
                elements.castButton.style.opacity = '0.5';
            }
        };
    }

    // Show start page by default (don't auto-load YouTube)
    showStartPage();

    // Handle share target (if launched via Web Share Target API)
    if (navigator.share && window.location.search) {
        const params = new URLSearchParams(window.location.search);
        const sharedUrl = params.get('url') || params.get('text');
        if (sharedUrl) {
            navigateToUrl(sharedUrl);
        }
    }

    // Handle URL in hash (for deep linking)
    if (window.location.hash) {
        const hashUrl = window.location.hash.slice(1);
        if (hashUrl) {
            navigateToUrl(decodeURIComponent(hashUrl));
        }
    }

    console.log('Web Video Caster ready!');
}

// Start when DOM is ready
if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
} else {
    init();
}

// Export for debugging
window.WebVideoCaster = {
    state,
    elements,
    loadMedia,
    navigateToUrl,
    extractVideoDirect,
};