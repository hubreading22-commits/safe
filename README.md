# SafeBrowser

A secure Android browser with remote blocklist configuration and video blocking.

## Features

- **Remote Blocklist**: Fetches blocked domains from your server
- **Video Blocking**: Blocks all video playback via JavaScript injection
- **Address Bar**: Users can browse normally, blocked sites show error page
- **Fallback List**: Works offline with cached blocklist
- **Auto Refresh**: Updates blocklist every 30 minutes

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
    "tiktok.com",
    "netflix.com",
    "twitter.com",
    "instagram.com"
  ],
  "blocked_keywords": [
    "porn",
    "gambling",
    "torrent"
  ],
  "video_blocking": true
}
```

### 3. Build via GitHub Actions

1. Push this repo to GitHub
2. GitHub Actions will automatically build APKs on every push
3. Download APKs from Actions tab or Releases

### 4. Deploy via MDM

- Download APK from GitHub Actions artifacts
- Upload to your MDM (ManageEngine, etc.)
- Push to devices as private enterprise app
- Set as default browser or lock device to this app

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

- Change `REMOTE_CONFIG_URL` to your server
- Modify `fallbackBlockedDomains` for offline protection
- Adjust `REFRESH_INTERVAL_MS` for update frequency (default: 30 min)

## License

MIT
