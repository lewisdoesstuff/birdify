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
| Header logo       | animated X    | animated bird     |
| Dark mode         | lights out    | dim               |
| Wording           | Post / Repost | Tweet / Retweet   |
| Grok tab          | in the nav    | removed           |
| Grok post button  | on each post  | removed           |

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
  icon resources by name and substitutes the bundled bird, serves an animated
  bird for the header logo, rewrites Post/Repost → Tweet/Retweet in the app's own
  strings, switches the dark theme to dim, and removes the Grok tab and post
  button.

## Dark mode

This is the part that breaks most often, so it is written down in full.

X's settings offer only light and dark, and dark is the OLED black that used to
be called lights out. The classic dim theme is still in the app, though. Two
palettes are involved, and each needs its own fix.

### 1. The theme enum (`DimTheme.kt`)

X's Compose theme is chosen by an enum with three values, `STANDARD`, `DIM` and
`LIGHTS_OUT`, and X still ships a complete dim palette for `DIM` (`#15202B`
background, `#38444D` divider and so on). One small function maps the user's
setting onto that enum, `(mode, darkVariant, systemIsDark) -> enum`. It returns
`LIGHTS_OUT` for both dark variants, so `DIM` is never selected.

`DimTheme.kt` hooks that function and turns a `LIGHTS_OUT` result into `DIM`, so
the timeline and most of the app draw dim with X's own colours. The enum's
constants keep their names through R8. The hook therefore checks that the class
it found really has `DIM` and `LIGHTS_OUT` before installing.

### 2. The bars' palette (`DimSurfaces.kt`)

The app bar, status bar and bottom nav draw from a second, design-system
palette (`XColors`). It has one light instance and one dark instance, chosen only
by light versus dark, so the enum never reaches it and the bars stay lights out.
Its dark backgrounds come from three static colour fields, and the dark instance
is the only thing that reads them:

| `XColors` role      | X ships   | Birdify writes |
| ------------------- | --------- | -------------- |
| backgroundPrimary   | `#030303` | `#15202B`      |
| backgroundSecondary | `#141414` | `#1C2C3C`      |
| backgroundTertiary  | `#262626` | `#22303C`      |

`DimSurfaces.kt` swaps those fields when the package loads, before the dark
instance is built. Each field is compared with the value X ships before writing
and read back afterwards. A renamed field is logged and left alone, so the bars
stay lights out rather than something else being repainted.

Compose packs a `Color` into a ULong with the ARGB in the high word, so field
values are `argb shl 32` rather than plain `0xAARRGGBB`.

### Why not repaint the palette by value?

Earlier versions did exactly that. They swapped dark greys on X's main palette
class by field, and that approach falls apart on an update:

- Most of the main palette's fields are shared across themes. In 12.41.0 the
  `#0F1419` that birdify used to repaint is both the dark ramp's base and light
  mode's text colour.
- The other greys moved to fields that only debug screens read.
- The old class name was reused by R8 for an unrelated class, so a check that
  the class merely existed still passed.

Selecting X's own `DIM` theme avoids all of this for the main UI. The bars'
palette is the one place a field swap is still needed, and it is only safe
because those three fields have exactly one reader.

### Finding them again after an X update

R8 renames both classes on most releases. `resync.sh` names the members it
expects, so start there. If one is reported missing, find it again in a jadx
decompile:

- **Theme enum:** search for the string `"LIGHTS_OUT"`. That finds the enum
  class, whose constants are not obfuscated.
- **Theme mapper:** find the method that returns that enum and contains
  `return <enum>.LIGHTS_OUT` twice, once per dark variant. Its parameters are two
  small setting enums and a boolean. Update `THEME_MAPPER` and `THEME_ENUM` in
  `DimTheme.kt`.
- **Bars' palette:** search for `"XColors(backgroundPrimary="`, the data class's
  `toString`. Its static initialiser builds the dark instance from fields on one
  colour class. The first three arguments are the primary, secondary and
  tertiary backgrounds. Check that each field has no other reader, then update
  `COLOURS` and the field names in `DimSurfaces.kt`.
- **Checks:** update the matching lines in `SYMBOL_CHECKS` in `resync.sh`.

Then check on a device that the module log shows `lights out replaced with dim`
and `dim surfaces applied 3/3`.

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

To find the composable again after a rename, take the static method that most
X icon call sites go through. Its first parameter is the icon wrapper class
(`com.x.icons.b`, whose int field `a` is the drawable id). Its signature is
`(icon, contentDescription, Modifier, long tint, boolean, Composer, int, int)`.

## The header bird

The logo in the home header is not an icon but a Lottie animation,
`raw/x_refresh_logo.json`. It rests on frame 0 and is driven by frame number
when you pull to refresh: frames 0–100 follow the pull, 100–152 loop while
refreshing, and 152–189 play on release. `BirdRefresh.kt` resolves that resource
by name and serves `app/src/main/res/raw/bird_refresh.json` in its place.
That file keeps X's canvas, frame rate and frame ranges, so X's own code plays
the bird:

- **Pull:** the bird crouches, twitches its wing and springs into a hover.
- **Loop:** it flaps.
- **Release:** it shrinks into a loop-the-loop, drawing a trail behind it, then
  flies back up onto its perch.

X recolours its own layers by layer name, so the bird's layer is named
differently and keeps its own Twitter blue.

The JSON is generated by `tools/bird-refresh/gen.py` from the same path as
`bird_blue.xml`. Edit the generator rather than the JSON:

```sh
python3 tools/bird-refresh/gen.py app/src/main/res/raw/bird_refresh.json
```

## Removing Grok

**The tab.** X builds the bottom bar from every tab it knows. It drops the Grok
tab only when the `android_webview_grok_tab_enabled` feature switch is off, and
that switch defaults to on. `GrokTab.kt` answers that one switch with false
through `com.x.featureswitches.FeatureSwitchesRepositoryImpl`. The feature-switch
store keeps its real names through R8, so this needs no obfuscated symbol.
`resync.sh` warns if the store or the switch key disappears.

**The post button.** The server marks each post that should offer "Explain this
post with Grok". X copies that mark onto the post's UI model (`AvailablePost`) as
`showAskGrokButton`, and `GrokButton.kt` clears it as each model is built.

The model and the field are both obfuscated. The model's `toString` would
identify the field, but it throws at runtime, because a nested function
reference has lost the reflection metadata it needs. So `resync.sh` pins the
field from the bytecode instead: the first boolean that `toString` reads after
printing `showAskGrokButton=` must be the field `GrokButton.kt` names.

To find the field again, search a decompile for `"showAskGrokButton="`.

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

Birdify resolves resources by **name** at runtime and never hardcodes a resource
ID. X renumbers every resource ID on each release, and re-runs R8 with fresh
obfuscation, so IDs and obfuscated names are both invalidated by an update.

X can still break Birdify by renaming or deleting a resource we target. When that
happens, `resync.sh` will tell you exactly what changed:

```sh
./resync.sh ~/Downloads/com.twitter.android_12.41.0.apkm
```

It reports each resource Birdify depends on, flags the ones X no longer has, and
suggests replacements. It also checks every obfuscated member the module hooks,
by name and type signature, using `dexdump` from the SDK's build tools. Fixing a breakage should mean editing a name in
`app/src/main/java/ooo/lew/birdify/AppHook.kt` or renaming a file under
`overlay/src/main/res`, never chasing a new ID.

Two rules keep it durable: never hardcode a resource ID, and never reference an
obfuscated class. The bird is bundled in `app/src/main/res/drawable/bird_blue.xml`
because X no longer ships a bird vector to borrow. `DimTheme.kt`,
`DimSurfaces.kt`, `BirdTint.kt` and `GrokButton.kt` are the documented
exceptions, all described above. Each verifies its target at runtime, and `resync.sh` checks the members
they name.

### Known gaps

- Some screens hardcode lights out regardless of the theme setting, and stay
  black: the video tab, the Grok chat host and the payments cheque scanner.
- One theme helper uses the system's dark setting when the theme is `DIM`. With
  X set to dark while the phone is in light mode, parts of the UI may come out
  light.
- `ic_vector_x_circle` was removed in X 12.29.1, so the circular X badge keeps its
  original look. `ic_vector_x_card` belongs to a dynamic feature module that may
  not be installed.
- The post detail screen still has a Grok button in its top bar. X draws it
  unconditionally, with no flag to clear.
- Tested against X 12.41.0.

While I'll try and stay on top of updates, please open an issue if I haven't updated yet.

## License

[MIT](LICENSE)
