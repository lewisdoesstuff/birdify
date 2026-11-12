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
| Grok prompts      | menu, posts   | removed           |
| Ads               | promoted      | removed           |
| Premium upsells   | throughout    | removed           |

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
  strings, switches the dark theme to dim, removes Grok's entry points, drops
  ads, and turns off Premium upsells. The obfuscated members those hooks need are
  found on the device, so most X updates need no new release (see
  [Version support](#version-support)).

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
constants keep their names through R8, which is how the enum is found: it is
the one enum with `STANDARD`, `DIM` and `LIGHTS_OUT`. The mapper is then the one
method taking two values and a boolean that returns it and reads `LIGHTS_OUT`.

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
instance is built. The palette is found by its `toString` label,
`XColors(backgroundPrimary=…`, and the candidates are the static colour fields
its initialiser reads that nothing else in X reads. Each grey is picked from
those by the value X ships, and read back after writing. A grey that is missing
or ambiguous is logged and left alone, so that bar stays lights out rather than
something else being repainted.

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
palette is the one place a field swap is still needed. It is only safe because
it is limited to fields with exactly one reader, the bars' dark palette.

### Checking it on a device

The module log should show `dim theme hook installed`, `dim surfaces applied
3/3` and, with X set to dark, `lights out replaced with dim`.

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

The composable is the one method with the signature
`(icon, contentDescription, Modifier, long tint, boolean, Composer, int, int)`,
where the icon wrapper is the class whose `toString` prints `Icon(drawableRes=`.
The wrapper's drawable id is the field that label prints.

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

## Feature switches

Much of what X shows is gated by server-controlled feature switches, read
through `com.x.featureswitches.FeatureSwitchesRepositoryImpl`. That class
keeps its real names through R8, so `Switches.kt` can answer chosen switches
with false without any obfuscated symbol. X reads a switch with either `get`,
which records an impression, or `peek`, which does not, so both are answered.

Every switch in its `OFF` table defaults to off in X's own code, so answering
false makes X behave as if the server had never turned the feature on:

- **Grok:** the nav tab and its badge, the Grok bot in the side menu, and the
  Imagine and "Download Grok" prompts on posts, media and the photo editor.
  Grok's translations are left alone.
- **Programmatic ads:** the Google-served ads X places on the home timeline,
  under posts, in the video tab, on profiles and in Spotlight. X adds these
  itself, so they never reach the timeline item builder that `TimelineAds.kt`
  hooks.
- **Premium upsells:** the header's Upgrade pill, the paywall on launch, the
  drawer and profile cards, the composer prompts, and "Boost this post".

`resync.sh` reads the table from `Switches.kt` and checks that each key still
exists in X. A missing key is a warning rather than a failure, since X retires
experiments often. The module log names each switch the first time it is
answered.

To find candidates on a new X build, list the switch store's keys and values,
or log the keys X reads along with their callers. Prefer switches that default
to off.

## Removing the Grok button on posts

The server marks each post that should offer "Explain this post with Grok". X
copies that mark onto the post's UI model (`AvailablePost`) as
`showAskGrokButton`, and `GrokButton.kt` clears it as each model is built. No
switch controls it.

The model and the field are both obfuscated, and the model's `toString` throws
at runtime, because a nested function reference has lost the reflection
metadata it needs. So the field is found from that `toString`'s bytecode
instead. Kotlin reads each property once, in the order it prints them, so the
field read for the `showAskGrokButton=` label is the one whose position matches
that label's. The label and read counts have to agree, and the field has to be
a boolean.

## Removing ads

X stores timeline entries locally and builds each displayed item from its row
through one function. That function already returns nothing for rows it cannot
show. Every ad carries promoted metadata (`TimelinePromotedMetadata`), whether
it is a promoted post, a promoted account or an image ad. `TimelineAds.kt`
returns nothing for any item that has some, so ads are removed before they are
laid out and leave no gap.

R8 renames both the function and the metadata class. The metadata class is
the one whose `toString` prints `TimelinePromotedMetadata(`. The function is
the one taking a stored row and two `LinkedHashMap`s that constructs
`UrtTimelinePost`, the class printing `UrtTimelinePost(`.

The hook logs each new kind of timeline item once, by class and model name.
That is how to identify any other card worth removing.

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

X renumbers every resource ID on each release, and re-runs R8 with fresh
obfuscation, so IDs and obfuscated names are both invalidated by an update.
Birdify depends on neither:

- **Resources** are resolved by name at runtime.
- **Obfuscated members** are found at runtime by `Targets.kt`. Each one is
  identified by something R8 leaves alone: a `toString` label, an enum
  constant's name, or a signature made of unobfuscated types. The sections
  above describe each search. [DexKit](https://github.com/LuckyPray/DexKit) runs
  them over X's dex the first time a new X build starts, which takes under a
  second. The results are cached in X's cache directory against the APK and the
  module, so later launches skip it.

Every search must match exactly once. If X changes enough that one finds
nothing or several candidates, the log says so (`targets found in … ms: 6/7,
missing [...]`) and that hook stays off, while the rest keep working.

`resync.sh` repeats the same searches against an APK with `dexdump`, so a
release that breaks one can be spotted without a device:

```sh
./resync.sh ~/Downloads/com.twitter.android_12.41.0.apkm
```

It also reports each resource Birdify depends on, flags the ones X no longer
has, and suggests replacements. Fixing a breakage should mean adjusting a
search in `Targets.kt`, editing a name in `AppHook.kt` or renaming a file under
`overlay/src/main/res`, never chasing a new ID.

Two rules keep it durable: never hardcode a resource ID, and never name an
obfuscated class or member. CI rejects both. The bird is bundled in
`app/src/main/res/drawable/bird_blue.xml` because X no longer ships a bird
vector to borrow.

### Known gaps

- Some screens hardcode lights out regardless of the theme setting, and stay
  black: the video tab, the Grok chat host and the payments cheque scanner.
- One theme helper uses the system's dark setting when the theme is `DIM`. With
  X set to dark while the phone is in light mode, parts of the UI may come out
  light.
- `ic_vector_x_circle` was removed in X 12.29.1, so the circular X badge keeps its
  original look. `ic_vector_x_card` belongs to a dynamic feature module that may
  not be installed.
- The post detail screen still has a Grok button in its top bar, and the side
  menu still has "Get Grok". X draws both unconditionally, with no flag or
  switch to clear.
- Tested against X 12.41.0.

While I'll try and stay on top of updates, please open an issue if I haven't updated yet.

## License

[MIT](LICENSE)
