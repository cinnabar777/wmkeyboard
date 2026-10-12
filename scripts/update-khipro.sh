#!/usr/bin/env bash
# Pulls the Khipro team's latest published layout definitions and conformance
# tables into the tree (issue #489).
#
#   scripts/update-khipro.sh        # fetch, then run KhiproTest against them
#
# Khipro is updated on a rolling basis, so the copies under resources/khipro/
# go stale between releases. Fetching them at build time instead would make
# every build depend on GitHub being reachable, and F-Droid builds offline from
# a fixed source tree, so they are refreshed here and committed like any other
# change. The interpreter in Khipro.kt runs the files verbatim; a test failure
# after an update is a spec feature the interpreter does not handle yet, not a
# table to edit.

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MAIN="$ROOT/core/language/src/main/resources/khipro"
TEST="$ROOT/core/language/src/test/resources/khipro"
RAW="https://raw.githubusercontent.com"

fetch() {
    local url="$1" dest="$2" tmp
    tmp="$(mktemp)"
    curl -fsSL "$url" -o "$tmp"
    if cmp -s "$tmp" "$dest"; then
        echo "unchanged  ${dest#"$ROOT"/}"
        rm -f "$tmp"
    else
        mv "$tmp" "$dest"
        echo "updated    ${dest#"$ROOT"/}"
    fi
}

# Each spec repository carries its own conformance table beside the spec.
# Classic's files were renamed bn-khipro-classic.mim when Dark (issue #631)
# arrived; the local names stay as they were.
fetch "$RAW/rank-coder/khipro-m17n/HEAD/bn-khipro-classic.mim" "$MAIN/bn-khipro.mim"
fetch "$RAW/rank-coder/khipro-m17n/HEAD/khipro-testcases.tsv" "$TEST/khipro-testcases.tsv"
fetch "$RAW/KhiproTeam/khipro-mim-touchscreen/HEAD/bn-khipro-classic.mim" "$MAIN/bn-khipro-touchscreen.mim"
fetch "$RAW/KhiproTeam/khipro-mim-touchscreen/HEAD/khipro-testcases.tsv" "$TEST/khipro-testcases-touch.tsv"
fetch "$RAW/KhiproTeam/khipro-dark-m17n/HEAD/bn-khipro-dark.mim" "$MAIN/bn-khipro-dark.mim"
fetch "$RAW/KhiproTeam/khipro-dark-m17n/HEAD/khipro-testcases.tsv" "$TEST/khipro-dark-testcases.tsv"
fetch "$RAW/KhiproTeam/khipro-dark-mim-touchscreen/HEAD/bn-khipro-dark.mim" "$MAIN/bn-khipro-dark-touchscreen.mim"
fetch "$RAW/KhiproTeam/khipro-dark-mim-touchscreen/HEAD/khipro-testcases.tsv" "$TEST/khipro-dark-testcases-touch.tsv"

if [[ -z "${JAVA_HOME:-}" ]]; then
    export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
fi
cd "$ROOT"
./gradlew :core:language:testFullDebugUnitTest --tests '*KhiproTest*'
