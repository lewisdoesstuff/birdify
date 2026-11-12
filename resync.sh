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

# Targets.kt finds every obfuscated member the hooks need on the device, by
# anchors R8 leaves alone: toString labels, enum constant names and signatures
# made of unobfuscated types. DexKit has no desktop build, so the same searches
# are repeated here with dexdump. Each must match exactly once, as it must on
# the device, and what it finds is printed so a change is easy to follow.

# Lists every field and method in the APK's dex as "Lclass; name type dex".
index_members() {
    local dex
    DEXDUMP=$(dirname "$AAPT2")/dexdump
    [ -x "$DEXDUMP" ] || die "dexdump not found next to $AAPT2"
    mkdir -p "$WORKDIR/dex"
    unzip -o -q "$APK" 'classes*.dex' -d "$WORKDIR/dex"
    for dex in "$WORKDIR"/dex/classes*.dex; do
        "$DEXDUMP" "$dex" 2>/dev/null | awk -v dex="$dex" '
            /^ *#[0-9]+ *: \(in L/ { cls = $NF; sub(/^\(in /, "", cls); sub(/\)$/, "", cls); next }
            /^ *name *: / { name = $3; gsub(/\047/, "", name); next }
            /^ *type *: / && cls != "" { type = $3; gsub(/\047/, "", type); print cls, name, type, dex; cls = "" }
        '
    done > "$WORKDIR/members.txt"
}

# Escapes a dex type for use in an extended regex.
regex() {
    printf '%s' "$1" | sed 's/[][\.*^$+?(){}|]/\\&/g'
}

# Prints the members whose "Lclass; name type" matches the given regex.
members_matching() {
    awk '{ print $1, $2, $3 }' "$WORKDIR/members.txt" | grep -E "^$1\$"
}

# Prints the bytecode of one method, given as class, name and type.
method_code() {
    local cls=$1 name=$2 type=$3 dex
    dex=$(awk -v c="$cls" -v n="$name" -v t="$type" '$1 == c && $2 == n && $3 == t { print $4; exit }' "$WORKDIR/members.txt")
    [ -n "$dex" ] || return 0
    "$DEXDUMP" -d "$dex" 2>/dev/null | awk -v c="$cls" -v n="$name" -v t="$type" '
        /^ *#[0-9]+ *: \(in L/ { cur = $NF; sub(/^\(in /, "", cur); sub(/\)$/, "", cur); inside = 0; next }
        /^ *name *: / { nm = $3; gsub(/\047/, "", nm); next }
        /^ *type *: / { ty = $3; gsub(/\047/, "", ty); inside = (cur == c && nm == n && ty == t); next }
        inside && /\|[0-9a-f]+: / { print }
    '
}

# Prints the class whose code holds a string constant starting with the label.
label_owner() {
    local label=$1 dex
    for dex in "$WORKDIR"/dex/classes*.dex; do
        grep -aqF "$label" "$dex" || continue
        "$DEXDUMP" -d "$dex" 2>/dev/null | awk -v label="$label" '
            /^ *#[0-9]+ *: \(in L/ { cls = $NF; sub(/^\(in /, "", cls); sub(/\)$/, "", cls) }
            index($0, "const-string") && index($0, "\"" label) { print cls; exit }
        '
    done | head -1
}

# Prints the field a data class's toString reads for one label. Kotlin reads
# each property once, in the order it prints them, so the n-th label belongs
# to the n-th field read, as Targets.labelledField assumes.
labelled_field() {
    local prefix=$1 name=$2 owner
    owner=$(label_owner "$prefix")
    [ -n "$owner" ] || return 0
    method_code "$owner" toString '()Ljava/lang/String;' | awk -v own="$owner." -v name="$name" '
        /const-string/ {
            s = $0; sub(/^[^"]*"/, "", s); sub(/" \/\/ string@[0-9a-f]+$/, "", s)
            if (s !~ /=$/) next
            sub(/=$/, "", s); n = split(s, parts, ", "); s = parts[n]; sub(/^.*\(/, "", s)
            labels[++nl] = s; next
        }
        /iget/ && index($0, own) { f = $0; sub(/.*, /, "", f); sub(/ .*/, "", f); reads[++nr] = f }
        END { if (nl != nr) exit; for (i = 1; i <= nl; i++) if (labels[i] == name) print reads[i] }
    '
}

# Keeps the "Lclass; name type" lines on stdin whose code contains the text.
code_contains() {
    local text=$1 cls name type
    while read -r cls name type; do
        method_code "$cls" "$name" "$type" | grep -qF "$text" && echo "$cls $name $type"
    done
}

# Reports one search: its result, which must be a single line.
target() {
    local what=$1 desc=$2 found=$3 count
    count=$(printf '%s' "$found" | grep -c . || true)
    if [ "$count" -eq 1 ]; then
        printf '  ok       %-14s %s is %s\n' "$what" "$desc" "$found"
    else
        printf '  MISSING  %-14s %s matched %s times\n' "$what" "$desc" "$count"
        printf '           <-- update the search in Targets.kt\n'
        FAILURES=$((FAILURES + 1))
    fi
}

echo
echo "Hook targets found on the device (Targets.kt)"
index_members

theme=$(awk '($2 == "STANDARD" || $2 == "DIM" || $2 == "LIGHTS_OUT") && $1 == $3 { n[$1]++ }
    END { for (c in n) if (n[c] == 3) print c }' "$WORKDIR/members.txt")
target "dim theme" "theme enum" "$theme"
if [ -n "$theme" ]; then
    mapper=$(members_matching "[^ ]+ [^ ]+ \(L[^;]+;L[^;]+;Z\)$(regex "$theme")" |
        code_contains "${theme}.LIGHTS_OUT:")
    target "dim theme" "theme mapper" "$mapper"
fi

palette=$(label_owner "XColors(")
target "dim bars" "XColors" "$palette"

icon=$(label_owner "Icon(drawableRes=")
target "bird tint" "icon wrapper" "$icon"
if [ -n "$icon" ]; then
    target "bird tint" "icon composable" "$(members_matching "[^ ]+ [^ ]+ \($(regex "$icon")Ljava/lang/String;Landroidx/compose/ui/Modifier;JZLandroidx/compose/runtime/Composer;II\)V")"
    target "bird tint" "drawableRes" "$(labelled_field "Icon(drawableRes=" drawableRes | grep ':I$')"
fi

target "grok button" "showAskGrokButton" "$(labelled_field "AvailablePost(" showAskGrokButton | grep ':Z$')"

target "timeline ads" "TimelinePromotedMetadata" "$(label_owner "TimelinePromotedMetadata(")"
post=$(label_owner "UrtTimelinePost(")
target "timeline ads" "UrtTimelinePost" "$post"
if [ -n "$post" ]; then
    builder=$(members_matching '[^ ]+ [^ ]+ \(L[^;]+;Ljava/util/LinkedHashMap;Ljava/util/LinkedHashMap;L[^;]+;\).*' |
        code_contains "${post}.<init>")
    target "timeline ads" "item builder" "$builder"
fi

echo
echo "Feature switches answered (Switches.kt)"
# Not obfuscated, but X can move the store or retire a switch. A retired
# switch is only a warning: X drops experiments often, and the feature may
# have gone with it. A dex string is its length byte, its text and a NUL,
# which keeps one key from matching inside a longer one. The length byte can
# be a digit, so only letters and underscores rule a match out.
for getter in getBoolean peekBoolean; do
    if members_matching "Lcom/x/featureswitches/FeatureSwitchesRepositoryImpl; $getter \\(Ljava/lang/String;Z\\)Z" >/dev/null; then
        printf '  ok       FeatureSwitchesRepositoryImpl.%s\n' "$getter"
    else
        printf '  MISSING  FeatureSwitchesRepositoryImpl.%s <-- Switches.kt goes inert\n' "$getter"
        FAILURES=$((FAILURES + 1))
    fi
done
SWITCH_KEYS=$(sed -n '/val OFF = setOf(/,/^    )/p' app/src/main/java/ooo/lew/birdify/Switches.kt |
    grep -oE '"[a-z0-9_]+"' | tr -d '"')
[ -n "$SWITCH_KEYS" ] || die "no switch keys found in Switches.kt"
for key in $SWITCH_KEYS; do
    if grep -aqP "(?<![a-z_])$key\x00" "$WORKDIR"/dex/classes*.dex; then
        printf '  ok       %s\n' "$key"
    else
        printf '  gone     %s <-- X no longer reads it; drop it or find its replacement\n' "$key"
    fi
done

echo
if [ "$FAILURES" -gt 0 ]; then
    echo "FAIL: $FAILURES problem(s) found. See above for what to change."
    echo "Pick replacements from the candidates above, update the source, re-run."
    exit 1
fi
echo "OK: nothing needs changing for this X build."
