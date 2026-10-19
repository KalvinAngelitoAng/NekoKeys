# NekoKeys - Custom Android Input Method Editor

A lightweight, privacy-focused keyboard application for Android built entirely in Kotlin without external dependencies or network permissions.

## Overview

NekoKeys is a high-performance custom Input Method Editor (IME) designed as a complete replacement for stock keyboard solutions. The application prioritizes user privacy, memory efficiency, and advanced text input capabilities while maintaining a zero-dependency architecture.

**Key Specifications:**
- **Language:** Pure Kotlin
- **Dependencies:** None (Android Framework only)
- **Network Permissions:** None
- **Minimum API Level:** 29 (Android 10)
- **Target API Level:** 34+ (Android 14+)

---

## Features

### Core Input Methods
- **Multi-layout Support:** QWERTY layout with dual-mode symbol pages, dedicated numpad, and pill-shaped keys matching modern IME standards
- **Gesture Input:** Full glide typing support with position-aware autocorrection
- **Predictive Text:** Context-aware word prediction for English and Indonesian
- **Language Modes:** 
  - English (single)
  - Indonesian (single)
  - English • Indonesian (hybrid mode with automatic language detection)

### Dictionary & Correction Engine
- **English Dictionary:** 60,000 entries (frequency-sorted from subtitle corpus)
- **Indonesian Dictionary:** 50,000 entries (including colloquial variants: gue, nggak, banget, yg, dgn, etc.)
- **Autocorrect Performance:**
  - English: 90% typo correction rate (mixed typos)
  - Indonesian: 93% typo correction rate
  - Zero false positives on 1,600 valid word corpus
- **Glide Typing Accuracy:**
  - English: 82% top-1, 93% top-3 accuracy
  - Indonesian: 86% top-1, 96% top-3 accuracy

### Personalization Engine
- **Adaptive Learning:** Long-term learning from user input with hard memory limits:
  - Maximum 5,000 custom words
  - Maximum 40,000 word pairs
  - Maximum 30 follower words per term
  - Automatic fade-out mechanism every ~200K keystrokes (2-4 months)
- **Theme System:** 7 pre-configured themes plus unlimited custom color palettes
  - Default theme: "Keqing" (Genshin Impact-inspired purple/lavender palette)
  - Color wheel interface with hex code input
- **Wallpaper Support:** Custom image backgrounds from device storage

### Emoji & Symbol Panel
- **Complete Emoji Support:** 1,914 emojis (Emoji 17.0 compatible)
- **Single Scroll View:** All emojis in optimized scrollable layout
- **Category Navigation:** Icon-based quick-jump to emoji categories
- **Advanced Features:**
  - Skin tone modifiers
  - Recency tracking for frequently-used emojis
  - Kaomoji support
  - Custom GIF/sticker collections

### Additional Input Methods
- **Clipboard Manager:** Multi-item clipboard with:
  - Pin/unpin functionality
  - Automatic cleanup
  - Optional latest screenshot access
- **One-Handed Mode:** Optimized layout for single-hand operation
- **Cursor Navigation:** Spacebar swiping for cursor movement
- **Batch Delete:** Multi-word deletion via delete key swipe

---

## Memory & Battery Profile

**Memory Footprint:**
- Full dictionary (both languages): ~7 MB
- Adaptive learning data (10-year simulation): 0.1–0.7 MB
- Runtime heap: ~3 MB

**Performance Metrics:**
- Word learning time: ~1.5 microseconds per entry
- Predictable scaling for 10-year data projection
- No network overhead (zero network permissions)

**Battery Impact:**
Measure on your device:
```bash
adb shell dumpsys batterystats --reset
# Use NekoKeys for one full day cycle
adb shell dumpsys batterystats com.nekokeys.keyboard
```

Memory snapshot:
```bash
adb shell dumpsys meminfo com.nekokeys.keyboard
```

---

## Installation & Setup

### Prerequisites
- Android Studio (Flamingo or newer recommended)
- Android SDK API 29+
- Kotlin 1.9+

### Build Instructions

1. **Clone & Open Project**
   ```bash
   git clone https://github.com/KalvinAngelitoAng/NekoKeys.git
   cd NekoKeys
   ```

2. **Sync Gradle**
   - Open in Android Studio
   - File > Open > select NekoKeys folder
   - Wait for Gradle sync to complete

3. **Enable Developer Mode (Device)**
   - Settings > About phone > Software information
   - Tap "Build number" 7 times
   - Navigate to Settings > Developer options > enable USB debugging
   - Connect device via USB and authorize connection

4. **Build & Deploy**
   - Android Studio: Build Variants > select `release` (optimized + debug-signed)
   - Run > Run 'app' (or press Shift+F10)

5. **Activate Keyboard**
   - Open NekoKeys app on device
   - Tap "Aktifkan NekoKeys" (Enable NekoKeys)
   - Go to Settings > System > Languages & input > Default keyboard
   - Select NekoKeys

### Supported Devices
- **Tested:** Samsung Galaxy S24 Ultra (Android 14+)
- **Minimum:** Android 10 (API 29)
- **Recommended:** Android 12+ (API 31+)

---

## Dictionary Management

### Custom Dictionary Building

Rebuild or resize dictionaries using the provided utility:

```bash
python3 tools/build_dictionaries.py \
  --src <path/to/source/folder> \
  --en 80000 \
  --id 60000
```

**Requirements:**
```bash
pip install spylls
```

**Source Format:**
- Plain text file, one word per line
- Ordered by frequency (most common first)
- Example:
  ```
  the
  and
  to
  of
  a
  ```

**Dictionary Size Tuning:**
- Larger dictionaries (80K+ words): better coverage, higher memory cost (~9-10 MB)
- Default dictionaries (60K+50K): balanced coverage and memory (~7 MB)
- Smaller dictionaries (40K+30K): lower memory (~4 MB), potential misses on less common words

### Source Attribution
See `NOTICE.md` for dictionary sources and licensing information.

---

## Privacy & Data Policy

### What NekoKeys Does NOT Do
- No network connections
- No telemetry
- No crash reporting
- No analytics
- No cloud synchronization
- No dictionary updates from remote sources
- No ad SDKs or tracking

### Data Retention
- **Built-in Dictionaries:** Read-only from APK (English 60K, Indonesian 50K)
- **Adaptive Learning:** Stored locally only, bounded by strict limits
- **Backup Behavior:** Intentionally disabled—learning data will not sync to new devices or persist after uninstall

### Data Storage
- Adaptive learning stored via atomic writes (temp file + fsync + rename)
- Single-writer pattern ensures crash safety
- Data structure survives OS shutdown without corruption

**View current data size:** Settings > Privacy (in-app)

---

## Development & Testing

### Build Variants
- **Debug:** Full logging, unoptimized, debug-signed
- **Release:** Optimized, debug-signed (suitable for beta testing)

### Custom Testing

#### Memory Usage
```bash
adb shell dumpsys meminfo com.nekokeys.keyboard
```

Expected output for full dictionary + 1 month learning data: 10-15 MB peak heap

#### Battery Impact
```bash
# Reset stats
adb shell dumpsys batterystats --reset

# Use keyboard for target duration
# Then dump report
adb shell dumpsys batterystats com.nekokeys.keyboard
```

#### Logcat Monitoring
```bash
adb logcat | grep "NekoKeys\|NekoIME"
```

#### Performance Profiling
- Android Studio: Profiler > CPU, Memory, Energy tabs
- Typical idle drain: <1% per hour
- Active typing drain: varies by device (test with provided battery script)

---

## Architecture Overview

### Core Components
- **NekoIME.kt:** Main IME service and input handling
- **KeyboardView.kt:** Keyboard layout rendering and touch event processing
- **EmojiPanel.kt / EmojiGridView.kt:** Emoji selection and display
- **Predictor.kt:** Autocorrect and word prediction engine
- **Themes.kt:** Theme management and color customization
- **Prefs.kt:** User preference persistence
- **ClipStore.kt / ClipPanel.kt:** Clipboard management
- **WallpaperStore.kt:** Wallpaper storage and rendering
- **Layouts.kt:** Keyboard layout definitions
- **Icons.kt / IconView.kt:** Icon rendering

### Data Structures
- Dictionary: Memory-mapped binary format for fast lookup
- Adaptive Learning: SQLite-backed key-value store with bounded size
- Theme Cache: JSON serialization for quick restoration

---

## Known Limitations & Future Work

### Current Limitations
- No gesture customization (hardcoded swipe actions)
- No plugin system for additional dictionaries
- No multi-user profile support
- Learning data does not sync across devices

### Planned Improvements
- [ ] Customizable gesture mappings
- [ ] Dictionary selection UI
- [ ] Extended language support (Japanese, Korean, etc.)
- [ ] Optional cloud backup (opt-in, end-to-end encrypted)
- [ ] Sticker pack manager

---

## Contributing

This is a personal project, but feedback and bug reports are welcome. Please open an issue with:
- Device model and Android version
- Reproduction steps
- Screenshots/logs if applicable

---

## License

See `LICENSE` and `NOTICE.md` for licensing details.

---

## Support & Feedback

- **Issues:** https://github.com/KalvinAngelitoAng/NekoKeys/issues
- **Contact:** [Your contact info if desired]

---

## Changelog

### v1.0-beta (Current)
- Initial beta release
- Full dictionary support (EN 60K, ID 50K)
- 7 themes + custom color engine
- Emoji 17.0 support (1,914 emojis)
- Adaptive learning with bounded memory
- Glide typing with position-aware autocorrection
- Hybrid EN•ID language mode

---

**Status:** 🔄 Beta Testing  
**Last Updated:** October 2026

Built with ❤️ in pure Kotlin
