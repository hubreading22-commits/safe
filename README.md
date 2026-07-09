# SafeBrowser

A premium, tablet-optimized, secure Android browser featuring remote blocklist configuration, strict video blocking, and a highly polished UI.

## Features

### 🌟 Premium Tablet User Interface
- **Multi-Row Layout:** A professional, two-row interface mimicking desktop Chrome. Includes a dedicated, horizontally scrolling tab strip and a clean navigation bar.
- **Dynamic Theme Colors:** Automatically extracts the `<meta name="theme-color">` tag from web pages and smoothly animates the toolbar background to match the site's branding.
- **New Tab Page (NTP):** A clean, fast-loading local HTML start page featuring a central search box that safely routes queries.
- **Custom Error Pages:** User-friendly, stylized HTML error pages for network failures and blocked domains instead of generic system text.
- **Pull-to-Refresh:** Native `SwipeRefreshLayout` support allows intuitive pull-down gestures to reload the current page.

### 🛡️ Security & Blocking
- **Remote Blocklist:** Fetches and caches blocked domains from a remote server configuration.
- **Aggressive Video Blocking:** Stops video playback dead in its tracks via early JS DOM injection on all frames, protecting bandwidth and preventing unwanted media consumption.
- **Persistent Offline Protection:** Blocklists are cached persistently so the app remains secure even without an active internet connection.

### ⚙️ Core Browser Engine Improvements
- **Default Browser Capabilities:** Fully registers with Android as a default browser (`ACTION_VIEW`, `ACTION_WEB_SEARCH`), seamlessly handling external links and system web searches.
- **Advanced Loading Animations:** Mimics modern browsers by delaying the progress bar slightly to prevent flashing on fast connections, quickly jumping to 80%, and smoothly fading out upon completion.
- **HTTPS-to-HTTP Fallback:** Automatically downgrades and retries connections over standard HTTP if an HTTPS connection refuses or times out.
- **Intelligent Download Manager:** Routes downloaded files into appropriate public Android directories (Pictures, Movies, Documents, etc.) and tracks download history in a dedicated `DownloadsActivity`.

## Setup

### 1. Configure Server URL

Edit `app/src/main/java/com/yourcompany/safebrowser/MainActivity.kt` and change:
```kotlin
private const val REMOTE_CONFIG_URL = "https://yourserver.com/blocklist.json"
```

### 2. Server JSON Format

Create `blocklist.json` on your server:
```json
{
  "version": "1.0",
  "updated": "2024-01-15T10:00:00Z",
  "blocked_domains": [
    "youtube.com",
    "facebook.com",
    "tiktok.com"
  ],
  "blocked_keywords": [
    "porn",
    "gambling"
  ],
  "video_blocking": true
}
```

### 3. Build via GitHub Actions

1. Push this repo to GitHub.
2. GitHub Actions will automatically build APKs on every push.
3. Download APKs from the Actions tab or Releases.

### 4. Deploy via MDM

- Download the compiled APK.
- Upload to your Mobile Device Management (MDM) solution (ManageEngine, InTune, etc.).
- Push to devices as a private enterprise app.
- Set as the default browser or lock the device to this app via Kiosk mode.

## Manual Build

```bash
# On Linux/Mac
./gradlew assembleDebug
./gradlew assembleRelease

# On Windows
gradlew.bat assembleDebug
gradlew.bat assembleRelease
```

## Customization

- Change `REMOTE_CONFIG_URL` to your preferred server.
- Modify `fallbackBlockedDomains` for offline fallback protection out of the box.
- Adjust `REFRESH_INTERVAL_MS` for blocklist update frequency (default: 30 min).

## License

MIT
