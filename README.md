# Web Video Caster

A free, ad-free, open-source Android app that casts web videos to Chromecast/Google TV devices. Clone of Web Video Caster (com.instantbits.cast.webvideo) built with modern Android architecture.

## Features

- **Completely Free** - No ads, no in-app purchases, no subscriptions, no analytics, no crash reporters
- **Phone as Remote** - Video plays ONLY on the TV, never on the phone
- **No Popups/Ads** - All browsing happens inside the app's WebView; redirect/ad pages never shown
- **Buffer-Free Streaming** - Direct cast of stream URLs; no relay through phone
- **One-Tap Cast** - Share any video URL from browser → app → TV
- **Material 3 Design** - Modern, beautiful UI with dark theme support

## Architecture: Two Tiers

### Tier 1 - Direct Cast (Primary, Included in APK)
- In-app WebView browser for browsing video sites
- JavaScript injection extracts direct video URLs (mp4, m3u8, mpd, `<video src>`)
- Sends URL to TV via Google Cast SDK Default Receiver (CC1AD845)
- Works with: YouTube, Vimeo, direct links, news sites, many others
- **YouTube requires URL transform** (`youtube.com/watch?v=X` → `youtu.be/X`) for Default Receiver

### Tier 2 - Relay Server (Optional, Separate Module)
- For sites that hide video URLs (anti-scraping, JS-only players)
- Headless server loads page, extracts/decodes stream, forwards to TV
- Handles "any site" but requires server maintenance
- Built as separate Kotlin/Ktor module - not in APK

## Building the APK

### Prerequisites
- Android Studio Koala (2024.1.1) or later
- JDK 17+
- Android SDK 34

### Steps
1. Clone the repository
2. Open in Android Studio
3. Sync Gradle
4. Build → Generate Signed Bundle/APK → APK
5. Install on device (minSdk 21, targetSdk 34)

### Build Variants
- `debug` - For development, includes logging
- `release` - Optimized, unsigned (sign for distribution)

## Registering the Cast App

### Default Receiver (Free, No Console Fee)
This app uses Google's **Default Media Receiver** (App ID: `CC1AD845`):
- No $5 Cast Console registration required
- No custom receiver code needed
- Supports standard media formats (HLS, DASH, MP4, YouTube)

### If You Want a Custom Receiver
1. Go to [Google Cast SDK Developer Console](https://cast.google.com/publish/)
2. Pay $5 one-time fee
3. Create a new application → "Custom Receiver"
4. Host your receiver HTML (see `docs/custom-receiver.html`)
5. Update `default_receiver_app_id` in `strings.xml`

## Supported Sites (Tier 1)

| Site | Status | Notes |
|------|--------|-------|
| YouTube | ✅ Works | Uses `youtu.be` transform |
| Vimeo | ✅ Works | Embed player URL |
| Direct .mp4/.m3u8/.mpd | ✅ Works | Auto-detected |
| News sites (CNN, BBC, etc.) | ✅ Works | Video tags detected |
| Twitch | ⚠️ Limited | May need relay server |
| Netflix/Hulu/Disney+ | ❌ No | DRM protected |
| Facebook/Instagram | ⚠️ Limited | May need relay server |

## Running the Relay Server (Tier 2)

### Prerequisites
- JDK 17+
- Gradle (included via wrapper)

### Local Development
```bash
cd relay-server
./gradlew run
# Server starts on http://localhost:8080
```

### Docker
```bash
cd relay-server
docker build -t webvideo-relay .
docker run -p 8080:8080 webvideo-relay
```

### Production Deployment
```bash
./gradlew installDist
# Runs from build/install/relay-server/bin/relay-server
```

### Environment Variables
| Variable | Default | Description |
|----------|---------|-------------|
| `PORT` | 8080 | Server port |
| `HOST` | 0.0.0.0 | Bind address |
| `LOG_LEVEL` | INFO | Log level (DEBUG, INFO, WARN, ERROR) |

### API Endpoints

#### Health Check
```
GET /health
Response: {"status":"ok","timestamp":1234567890}
```

#### Extract Video URL
```
POST /extract
Content-Type: application/json
{"url": "https://example.com/video-page"}

Response:
{
  "success": true,
  "videoUrl": "https://cdn.example.com/video.m3u8",
  "mimeType": "application/vnd.apple.mpegurl",
  "title": "Video Title",
  "thumbnail": "https://example.com/thumb.jpg",
  "source": "relay-server"
}
```

```
GET /extract?url=https://example.com/video-page
# Same response as POST
```

### Integration with Android App
To use the relay server from the app:
1. Deploy relay server to a reachable URL (e.g., `https://relay.example.com`)
2. Add relay server URL to app settings (future feature)
3. When Tier 1 extraction fails, app can call relay server as fallback

## Project Structure

```
chromecast/
├── app/                          # Android APK (Tier 1)
│   ├── src/main/
│   │   ├── java/com/instantbits/cast/webvideo/
│   │   │   ├── WebVideoCasterApplication.kt
│   │   │   ├── cast/
│   │   │   │   ├── CastOptionsProvider.kt
│   │   │   │   ├── CastManager.kt
│   │   │   │   └── ExpandedControlsActivity.kt
│   │   │   ├── ui/
│   │   │   │   └── MainActivity.kt
│   │   │   └── video/
│   │   │       └── VideoExtractor.kt
│   │   ├── res/
│   │   │   ├── layout/
│   │   │   ├── values/
│   │   │   ├── drawable/
│   │   │   ├── menu/
│   │   │   └── xml/
│   │   └── AndroidManifest.xml
│   └── build.gradle.kts
├── relay-server/                 # Optional Relay Server (Tier 2)
│   ├── src/main/kotlin/com/instantbits/cast/relay/
│   │   └── RelayServer.kt
│   ├── src/main/resources/
│   │   └── application.conf
│   └── build.gradle.kts
├── settings.gradle.kts
├── build.gradle.kts
└── README.md
```

## Key Technical Details

### Cast SDK
- `com.google.android.gms:play-services-cast-framework:21.4.0`
- Default Receiver: `CC1AD845`
- MediaRouter for device discovery (mDNS)

### WebView Configuration
- JavaScript enabled
- DOM storage enabled
- Mixed content allowed
- Custom User-Agent for mobile compatibility
- No file/content access for security

### Video Extraction (JavaScript)
Injected into page to find:
1. `<video>` elements (`currentSrc`, `<source>`)
2. Regex scan for `.m3u8`, `.mpd`, `.mp4`, `.webm` in scripts/text
3. YouTube embeds & watch pages (convert to `youtu.be`)
4. Vimeo embeds
5. Open Graph / Twitter Card meta tags
6. JSON-LD structured data

### Permissions (Minimal)
```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
<uses-permission android:name="android.permission.WAKE_LOCK" />
```
**No location permission** - mDNS discovery works without it on Android 9+

## Contributing

1. Fork the repository
2. Create a feature branch
3. Make changes with tests
4. Submit a PR

### Code Style
- Kotlin with ktlint
- Material 3 components
- Coroutines for async
- Timber for logging

## License

MIT License - Free for personal and commercial use.

See [LICENSE](LICENSE) for details.

## Disclaimer

This app is for personal use only. Respect copyright and terms of service of video providers. The relay server component may require additional considerations for production use (rate limiting, caching, DRM compliance).

## Acknowledgments

- Google Cast SDK team
- Ktor framework
- Jsoup HTML parser
- Material Design team