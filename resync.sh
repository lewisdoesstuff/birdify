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
report "string/app_name" optional

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

echo
echo "Dark surface tokens (DarkTokens.kt)"
# The palette class and its field names are the one place birdify names an
# obfuscated symbol, because X keeps every theme's colours on one class and
# swapping by value would also hit light mode's black text. Checked here so a
# rename surfaces before the module goes quietly inert.
DARK_PALETTE=com/x/compose/core/i3
ICON_COMPOSABLE=com/x/compose/core/l1
symbol_present() {
    local needle=$1 member hits
    while read -r member; do
        [ -n "$member" ] || continue
        # -c so grep drains the stream and unzip is not SIGPIPE'd; || true because
        # a zero-match grep exits 1, which set -e would treat as fatal
        hits=$(unzip -p "$APK" "$member" 2>/dev/null | grep -acF "$needle" || true)
        [ "$hits" -gt 0 ] && return 0
    done < <(unzip -Z1 "$APK" 2>/dev/null | grep -E '^classes[0-9]*\.dex$')
    return 1
}

echo
echo "Obfuscated symbols the module hooks (isolated in their own files)"
for pair in "$DARK_PALETTE:dark surface tokens" "$ICON_COMPOSABLE:bird tint"; do
    needle=${pair%%:*}
    what=${pair#*:}
    if symbol_present "$needle"; then
        printf '  ok       %-24s %s\n' "${needle//\//.}" "$what"
    else
        printf '  MISSING  %-24s %s <-- inert on this build\n' "${needle//\//.}" "$what"
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
