# Birdify

Restores the classic Twitter branding in the X app (Android).

## What it changes

| Element           | Before        | After             |
| ----------------- | ------------- | ----------------- |
| Launcher icon     | black X       | blue bird         |
| App name          | "X"           | "Twitter"         |
| Splash screen     | black + X     | blue + bird       |
| Notification icon | X             | bird              |
| In-app X logo     | X             | blue bird         |
| Dark mode         | lights out    | dim               |
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
- **Xposed module** — a LSPosed module installed as a system app. It resolves X's
  icon resources by name and substitutes the bundled bird, rewrites
  Post/Repost → Tweet/Retweet in the app's own strings, and repaints the dark
  surfaces.

## Dark mode

X keeps every theme's colours as static longs on a single palette class, which
means the dark surfaces have to be swapped by field rather than by value:
replacing a colour globally would also catch the pure black that light mode uses
for text. `DarkTokens.kt` therefore names that one obfuscated class, and for each
field it compares the value X currently ships against the value it expects before
writing, then reads it back. A field that has been renamed is reported and left
alone rather than silently repainting the app.

That class is the only place birdify depends on an obfuscated symbol.
`resync.sh` checks it is still present, so a rename surfaces before the module
goes quietly inert. Compose packs a `Color` into a ULong with the ARGB in the
high word, so field values are `argb shl 32` rather than plain `0xAARRGGBB`.

## The bird's colour

X draws its logos through a composable that tints whatever vector it is given to
match the surrounding surface, so the substituted bird arrives black in light
mode and white in dark mode. `BirdTint.kt` names that composable and sets the
tint argument to Twitter blue.

The tint is a *defaulted* parameter, and the composable's last int is Compose's
default mask with one bit per parameter. While bit 3 is set the callee
substitutes `LocalContentColor` and discards the value passed in, so the bit has
to be cleared for the colour to take effect. Getting this wrong fails silently:
the hook reports success and the bird simply renders in the theme colour.

## Overlays spanning renames

X has renamed the resources it uses for the launcher icon and splash screen, so
the overlay declares every spelling it has used and lets whichever one exists on
the running build take effect. `overlay/target-aliases.txt` lists the groups, and
`resync.sh` requires at least one member of each group to still exist, so a
rename to a genuinely new name still fails the check.

## Building

Requires JDK 21 and the Android SDK (platform 35).

```sh
./release.sh
```

This builds both APKs and packages `birdify-ksu.zip`.

## Version support

Birdify resolves everything by **resource name** at runtime, and no longer hardcodes a
resource ID or names an obfuscated class. X renumbers every resource ID on each release,
and re-runs R8 with fresh obfuscation, so both of those are invalidated by an update.

X can still break Birdify by renaming or deleting a resource we target. When that
happens, `resync.sh` will tell you exactly what changed:

```sh
./resync.sh ~/Downloads/com.twitter.android_12.29.1.apkm
```

It reports each resource Birdify depends on, flags the ones X no longer has, and
suggests replacements. Fixing a breakage should mean editing a name in
`app/src/main/java/ooo/lew/birdify/AppHook.kt` or renaming a file under
`overlay/src/main/res`, never chasing a new ID.

Two rules keep it durable: never hardcode a resource ID, and never reference an
obfuscated class. The bird is bundled in `app/src/main/res/drawable/bird_blue.xml`
because X no longer ships a bird vector to borrow. `DarkTokens.kt` and
`BirdTint.kt` are the two documented exceptions, both described above, and
`resync.sh` checks the symbols they name are still present.

### Known gaps

- The dark repaint covers X's three base surface tokens. Derived surfaces follow,
  since they are computed from those. It does not reach the accent colours, which
  are already Twitter blue (`#1D9BF0`).
- `ic_vector_x_circle` was removed in X 12.29.1, so the circular X badge keeps its
  original look.
- Tested against X 12.17.0. `resync.sh` reports exactly what a newer build would
  need, but the two hooked composables are both renamed there, so the dark theme
  and the bird's colour would go inert until their new names are found.

While I'll try and stay on top of updates, please open an issue if I haven't updated yet.

## License

[MIT](LICENSE)
