# Birdify

Restores the classic Twitter branding in the X app (Android).

## What it changes

| Element           | Before        | After             |
| ----------------- | ------------- | ----------------- |
| Launcher icon     | black X       | blue bird         |
| App name          | "X"           | "Twitter"         |
| Splash screen     | black + X     | blue + bird       |
| Notification icon | X             | bird              |
| Header logo       | X             | blue bird         |
| Dark mode         | pure black    | "dim" (`#15202b`) |
| Wording           | Post / Repost | Tweet / Retweet   |

## Requirements

- Android 12+
- Root: KernelSU, Magisk, or APatch
- [LSPosed](https://github.com/LSPosed/LSPosed) (or a fork such as [Vector](https://github.com/JingMatrix/Vector))

## Install

1. Download `birdify-ksu.zip` from the [Releases](https://github.com/lewisdoesstuff/birdify/releases) page.
2. Install it as a module in KernelSU (or Magisk).
3. Reboot.
4. Open LSPosed, enable **Birdify**, and set its scope to **X** (`com.twitter.android`).
5. Force-stop X.

The single zip installs both the resource overlay and the Xposed module.

## How it works

Birdify is two components bundled into one module:

- **Overlay** — a static Runtime Resource Overlay (RRO) placed in
  `system/product/overlay/`. It overrides X's resources directly, so the launcher
  icon, app name, splash, and notification icon change system-wide.
- **Xposed module** — a LSPosed module installed as a system app. It hooks the app
  process to swap the header logo for a blue bird, restore the "dim" dark palette,
  and rewrite Post/Repost → Tweet/Retweet.

## Building

Requires JDK 21 and the Android SDK (platform 35).

```sh
./release.sh
```

This builds both APKs and packages `birdify-ksu.zip`.

## Version support

Birdify is pinned to the X app **v12.17.0**. The overlay is resource-name based and
survives app updates, but the Xposed module references resource IDs and obfuscated
class names that can shift between releases. If X updates and something stops working,
the values in `app/src/main/java/ooo/lew/birdify/AppHook.kt` may need updating.

While I'll try and stay on top of updates, please open an issue if I haven't updated yet.

## License

[MIT](LICENSE)
