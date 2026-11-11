#!/usr/bin/env bash
#
# Checks that the X app still exposes every resource Birdify depends on.
#
# Birdify resolves resources by name at runtime, so nothing needs regenerating
# after an X update. What can still break is X renaming or deleting a resource
# we target. This reports that, so you find out here instead of on your phone.
#
#   ./resync.sh path/to/X.apkm
#
# Exits non-zero if anything the module relies on has gone missing.
set -euo pipefail
cd "$(dirname "$0")"

BIRDIFY_PACKAGE=com.twitter.android
OVERLAY_RES=overlay/src/main/res
HOOK_SOURCE=app/src/main/java/ooo/lew/birdify/AppHook.kt

die() { echo "error: $*" >&2; exit 2; }

find_aapt2() {
    local sdk
    sdk=$(grep -oE 'sdk\.dir=.*' local.properties 2>/dev/null | cut -d= -f2- || true)
    for candidate in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "$sdk" "$HOME/Library/Android/sdk"; do
        [ -n "$candidate" ] || continue
        local found
        found=$(ls -1 "$candidate"/build-tools/*/aapt2 2>/dev/null | sort -V | tail -1)
        [ -n "$found" ] && { echo "$found"; return 0; }
    done
    command -v aapt2 2>/dev/null && return 0
    die "aapt2 not found. Set ANDROID_HOME or sdk.dir in local.properties."
}

# ---------------------------------------------------------------- extract ---
WORKDIR=$(mktemp -d)
trap 'rm -rf "$WORKDIR"' EXIT

INPUT=${1:-}
[ -n "$INPUT" ] || die "usage: $0 <X.apk|X.apkm>"
[ -f "$INPUT" ] || die "no such file: $INPUT"

case "$INPUT" in
    *.apkm)
        unzip -o -q "$INPUT" base.apk info.json -d "$WORKDIR" 2>/dev/null || true
        if [ -f "$WORKDIR/info.json" ]; then
            python3 - "$WORKDIR/info.json" <<'PY'
import json, sys
info = json.load(open(sys.argv[1]))
print(f"X version : {info['release_version']}  (versionCode {info['versioncode']})")
PY
        fi
        ;;
esac

APK="$WORKDIR/base.apk"
[ -f "$APK" ] || APK="$INPUT"

AAPT2=$(find_aapt2)
"$AAPT2" dump resources "$APK" > "$WORKDIR/res.txt" 2>/dev/null \
    || die "could not read resources from $APK"

# ----------------------------------------------------------------- lookup ---
# Prints the hex id for a fully qualified resource name, e.g. drawable/ic_vector_x
lookup() {
    awk -v want="${1#*/}" '
        /^ *resource / {
            name = $3
            sub(/^[a-z]+\//, "", name)
            if (name == want) { print $2; exit }
        }
    ' "$WORKDIR/res.txt"
}

# Splits a resource name into lowercase tokens for fuzzy candidate matching

# Lists same-type resources sharing name components with the given one, so
# ic_vector_x_circle suggests ic_vector_twitter_circle_fill rather than every
# abc_ic_* that happens to contain "ic". Scores by components matched.
candidates_for() {
    local qualified=$1
    local type=${1%%/*} name=${1#*/}
    local comps
    comps=$(tr '_' '\n' <<< "$name" | tr 'A-Z' 'a-z' | grep -v '^$' | paste -sd, -)
    awk -v type="$type" -v comps="$comps" '
        BEGIN { n = split(comps, parts, ","); for (i = 1; i <= n; i++) want[parts[i]] = 1 }
        /^ *resource / {
            full = $3
            if (index(full, type "/") != 1) next
            rest = substr(full, length(type) + 2)
            if (substr(rest, 1, 1) == "$") next
            m = split(rest, seg, "_")
            score = 0
            for (j = 1; j <= m; j++) if (tolower(seg[j]) in want) score++
            if (score >= 2) print score, $2, full
        }
    ' "$WORKDIR/res.txt" | sort -rn | awk 'NR <= 12 { print $2, $3 }'
}

FAILURES=0
report() {
    local qualified=$1 required=$2 id
    id=$(lookup "$qualified")
    if [ -n "$id" ]; then
        printf '  ok       %-34s %s\n' "$qualified" "$id"
        return
    fi
    printf '  MISSING  %-34s' "$qualified"
    if [ "$required" = "required" ]; then
        printf '  <-- the overlay needs this\n'
        FAILURES=$((FAILURES + 1))
    else
        printf '  (optional)\n'
    fi
    candidates_for "$qualified" | sed 's/^/             candidate: /'
}

echo
echo "X icons swapped for the bird at runtime (AppHook.kt)"
FOUND=0
for n in ic_vector_x ic_vector_x_card ic_vector_x_circle ic_logo_x; do
    [ -n "$(lookup "drawable/$n")" ] && FOUND=$((FOUND + 1))
    report "drawable/$n" optional
done
if [ "$FOUND" -eq 0 ]; then
    echo
    echo "  none of the X icon names exist, so there is nothing left to swap."
    echo "  Find their replacements above and update xIconNames in AppHook.kt."
    FAILURES=$((FAILURES + 1))
elif [ "$FOUND" -lt 4 ]; then
    echo
    echo "  note: $FOUND of 4 X icons found. The rest stay as the X logo."
fi
for n in APP_NAME app_name; do
    report "string/$n" optional
done

echo
echo "Header refresh logo served as the bird (BirdRefresh.kt)"
report "raw/x_refresh_logo" optional

echo
echo "Overlay targets (overlay/src/main/res)"
# Every resource the overlay declares is an attempt to override X, except our
# own birdify_-prefixed assets, which X is not expected to have.
overlay_targets() {
    find "$OVERLAY_RES" -type f -name '*.xml' 2>/dev/null | while read -r file; do
        rel=${file#"$OVERLAY_RES"/}
        case "$rel" in
            values/*)
                sed -n 's/.*<\(string\|color\|bool\|integer\|dimen\|style\)[ >].*name="\([^"]*\)".*/\1\/\2/p' "$file"
                ;;
            *)
                dir=${rel%%/*}
                base=${rel#*/}
                printf '%s/%s\n' "${dir%%-*}" "${base%.xml}"
                ;;
        esac
    done | grep -v '/birdify_' | sort -u
}
ALIAS_FILE=overlay/target-aliases.txt

# True when a name is one of several interchangeable spellings of a target.
alias_member() {
    [ -f "$ALIAS_FILE" ] || return 1
    grep -qF "$1" "$ALIAS_FILE"
}

while read -r qualified; do
    [ -n "$qualified" ] || continue
    # Alias members are checked as a group below, not individually.
    if alias_member "$qualified"; then
        continue
    fi
    report "$qualified" required
done < <(overlay_targets)

# Interchangeable spellings of the same target. One of each group is enough.
while IFS= read -r group; do
    case "$group" in ''|'#'*) continue ;; esac
    names=${group//|/ }
    present=""
    for name in $names; do
        if [ -n "$(lookup "$name")" ]; then
            present="$present $name"
        fi
    done
    if [ -n "$present" ]; then
        printf '  ok       %-58s via%s\n' "$group" "$present"
    else
        printf '  MISSING  %-58s <-- none of these exist in this X build\n' "$group"
        FAILURES=$((FAILURES + 1))
    fi
done < <(grep -vE '^\s*(#|$)' "$ALIAS_FILE" 2>/dev/null)

# DimTheme.kt, DimSurfaces.kt and BirdTint.kt are the only places birdify names an
# obfuscated symbol. R8 reuses short names, so a class called j or c exists in
# every build; what has to survive is the member the module hooks, with the
# shape it expects. Each check below is a member of the dex, written as
# "Lclass; name type", and a rename surfaces here before the module goes inert.
SYMBOL_CHECKS=(
    "DimTheme.kt|dim theme|Lcom/x/android/theme/api/j; a \(.*\)Lcom/x/android/theme/api/k;"
    "DimTheme.kt|dim theme|Lcom/x/android/theme/api/k; DIM Lcom/x/android/theme/api/k;"
    "DimTheme.kt|dim theme|Lcom/x/android/theme/api/k; LIGHTS_OUT Lcom/x/android/theme/api/k;"
    "DimSurfaces.kt|dim bars|Lcom/x/xds/core/c; c J"
    "DimSurfaces.kt|dim bars|Lcom/x/xds/core/c; v J"
    "DimSurfaces.kt|dim bars|Lcom/x/xds/core/c; u J"
    "BirdTint.kt|bird tint|Lcom/x/compose/core/j; d \(Lcom/x/icons/b;Ljava/lang/String;Landroidx/compose/ui/Modifier;JZLandroidx/compose/runtime/Composer;II\)V"
    "BirdTint.kt|bird tint|Lcom/x/icons/b; a I"
    "GrokButton.kt|grok button|Lcom/x/urt/items/post/a6; Y Z"
    "TimelineAds.kt|timeline ads|Lorg/chromium/support_lib_boundary/util/b; W \(.*\)Lcom/x/models/timelines/items/o0;"
)

# A class known only by the name its own toString prints, as "label|class".
OWNER_CHECKS=(
    "TimelineAds.kt|timeline ads|TimelinePromotedMetadata(|Lcom/x/models/gf;"
)

# Prints the class whose code holds the given string constant.
label_owner() {
    local label=$1 dexdump dex
    dexdump=$(dirname "$AAPT2")/dexdump
    for dex in "$WORKDIR"/dex/classes*.dex; do
        grep -aqF "$label" "$dex" || continue
        "$dexdump" -d "$dex" 2>/dev/null | awk -v label="$label" '
            /^ *#[0-9]+ *: \(in L/ { cls = $NF; sub(/^\(in /, "", cls); sub(/\)$/, "", cls) }
            index($0, "const-string") && index($0, "\"" label) { print cls; exit }
        '
    done | head -1
}

# A field whose meaning only its owner's toString records, as "label|field".
# The first boolean toString reads after printing the label must be the field,
# which pins it down even though R8 renames both the class and the field.
LABEL_CHECKS=(
    "GrokButton.kt|grok button|showAskGrokButton=|Lcom/x/urt/items/post/a6;.Y:Z"
)

# Prints the first boolean field read after the given label in any dex.
field_after_label() {
    local label=$1 dexdump dex
    dexdump=$(dirname "$AAPT2")/dexdump
    for dex in "$WORKDIR"/dex/classes*.dex; do
        grep -aqF "$label" "$dex" || continue
        "$dexdump" -d "$dex" 2>/dev/null | awk -v label="$label" '
            index($0, "const-string") && index($0, label) { armed = 1; next }
            armed && /iget-boolean/ { sub(/.*, /, ""); sub(/ .*/, ""); print; exit }
        '
    done | head -1
}

# Lists every field and method in the APK's dex as "Lclass; name type".
index_members() {
    local dexdump dex
    dexdump=$(dirname "$AAPT2")/dexdump
    [ -x "$dexdump" ] || die "dexdump not found next to $AAPT2"
    mkdir -p "$WORKDIR/dex"
    unzip -o -q "$APK" 'classes*.dex' -d "$WORKDIR/dex"
    for dex in "$WORKDIR"/dex/classes*.dex; do
        "$dexdump" "$dex" 2>/dev/null
    done | awk '
        /^ *#[0-9]+ *: \(in L/ { cls = $NF; sub(/^\(in /, "", cls); sub(/\)$/, "", cls); next }
        /^ *name *: / { name = $3; gsub(/\047/, "", name); next }
        /^ *type *: / && cls != "" { type = $3; gsub(/\047/, "", type); print cls, name, type; cls = "" }
    ' > "$WORKDIR/members.txt"
}

echo
echo "Obfuscated symbols the module hooks (isolated in their own files)"
index_members
for check in "${SYMBOL_CHECKS[@]}"; do
    IFS='|' read -r file what member <<< "$check"
    shown=${member//\\/}
    if grep -qE "^${member}\$" "$WORKDIR/members.txt"; then
        printf '  ok       %-14s %s\n' "$what" "$shown"
    else
        printf '  MISSING  %-14s %s\n' "$what" "$shown"
        printf '           <-- %s goes inert on this build\n' "$file"
        FAILURES=$((FAILURES + 1))
    fi
done
for check in "${OWNER_CHECKS[@]}"; do
    IFS='|' read -r file what label owner <<< "$check"
    found=$(label_owner "$label")
    if [ "$found" = "$owner" ]; then
        printf '  ok       %-14s %s is %s\n' "$what" "$label" "$owner"
    else
        printf '  MISSING  %-14s %s is %s, expected %s\n' "$what" "$label" "${found:-absent}" "$owner"
        printf '           <-- %s goes inert on this build\n' "$file"
        FAILURES=$((FAILURES + 1))
    fi
done
for check in "${LABEL_CHECKS[@]}"; do
    IFS='|' read -r file what label field <<< "$check"
    found=$(field_after_label "$label")
    if [ "$found" = "$field" ]; then
        printf '  ok       %-14s %s is %s\n' "$what" "$label" "$field"
    else
        printf '  MISSING  %-14s %s is %s, expected %s\n' "$what" "$label" "${found:-absent}" "$field"
        printf '           <-- %s goes inert on this build\n' "$file"
        FAILURES=$((FAILURES + 1))
    fi
done

echo
echo "Feature switches answered (GrokTab.kt)"
# Not obfuscated, but X can retire a switch or move the store.
if grep -qE '^Lcom/x/featureswitches/FeatureSwitchesRepositoryImpl; getBoolean \(Ljava/lang/String;Z\)Z$' "$WORKDIR/members.txt"; then
    printf '  ok       FeatureSwitchesRepositoryImpl.getBoolean\n'
else
    printf '  MISSING  FeatureSwitchesRepositoryImpl.getBoolean <-- GrokTab.kt goes inert\n'
    FAILURES=$((FAILURES + 1))
fi
for key in android_webview_grok_tab_enabled; do
    if grep -aqF "$key" "$WORKDIR"/dex/classes*.dex; then
        printf '  ok       %s\n' "$key"
    else
        printf '  MISSING  %s <-- X no longer reads it, so the tab has moved\n' "$key"
        FAILURES=$((FAILURES + 1))
    fi
done

echo
if [ "$FAILURES" -gt 0 ]; then
    echo "FAIL: $FAILURES problem(s) found. See above for what to change."
    echo "Pick replacements from the candidates above, update the source, re-run."
    exit 1
fi
echo "OK: nothing needs changing for this X build."
