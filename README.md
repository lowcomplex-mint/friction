# Friction

**A free, unlimited, open-source [one sec](https://one-sec.app/)-style app guard for Android.**

When you open an app you chose to guard (TikTok, Instagram, YouTube, …), Friction
drops an instant black pause screen: breathe, countdown, then  
**“Do you really want to open this?”** — with **No** as the primary action.

- Unlimited guarded apps  
- Fully local — no accounts, no analytics, no network  
- Sideload only (not on Play Store)  
- Min Android 8.0 (API 26)

---

## Credits

Inspired by **[one sec](https://one-sec.app/)** by **[riedel.wtf GmbH](https://riedel.wtf)**.  
Friction is **not affiliated** with one sec. See [NOTICE.md](NOTICE.md).

## Authorship

**This project contains no human-written application code.**  
It was entirely **vibe-coded** by **Grok 4.5** (xAI) and **Claude Sonnet** (Anthropic).  
See [NOTICE.md](NOTICE.md).

---

## Install (for humans / family)

1. Download the latest **APK** from [Releases](https://github.com/lowcomplex-mint/friction/releases)  
   (or use a zip from family if you received one).
2. On the phone: allow install from unknown sources for your file manager / browser.
3. Open the APK and install.
4. Open **Friction** and turn on the three setup rows:
   - **Accessibility → Friction app guard**
   - **Display over other apps** (required for the instant black screen)
   - **Ignore battery optimizations**
5. On Xiaomi / HyperOS also: App info → Battery → **No restrictions**, enable **Autostart** if present.
6. Pick apps to guard in the list. Open one — you should see the pause screen.

### Known limitation (also present in one sec–class apps)

If you tap **No** and immediately re-open the same app, the gate may not fire until
you wait a short moment. This is a platform settle / soft-kill race on Android;
waiting a beat after declining is the reliable path.

---

## Build from source

```bash
# Full JDK 17+ with jlink (not JRE-only)
export JAVA_HOME=/path/to/jdk-17
# Android SDK in local.properties: sdk.dir=...

./build.sh
# APK: app/build/outputs/apk/debug/app-debug.apk
```

---

## How it works (short)

1. `AccessibilityService` sees a guarded package come to the foreground.  
2. **Immediately** shows a full-screen `TYPE_APPLICATION_OVERLAY` (black Friction UI).  
3. Then sends Home + soft-kills the target **under** the curtain.  
4. After the countdown: **No** (primary) or **Yes** (secondary).  

Details and device notes: see docs in the repo / release notes.

---

## License

[MIT](LICENSE) — free to use, modify, and share.

---

## Morality / intent

This is a personal-use tool for intentional phone habits. It is not a clone meant
to undercut one sec commercially; it is a free, open experiment for people who
want unlimited guards and local-only software. If you can, support the original
[one sec](https://one-sec.app/) team — they did the hard product work first.
