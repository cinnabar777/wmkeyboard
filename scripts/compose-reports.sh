#!/usr/bin/env bash
# Compose compiler reports, built and summarised.
#
#   scripts/compose-reports.sh                     # feature/ime
#   scripts/compose-reports.sh feature/ime app     # several modules
#   scripts/compose-reports.sh --no-build app      # summarise what is already there
#
# Recompiles each module with -PcomposeMetrics=true (see
# build-logic/.../wmkeyboard.compose-metrics.gradle.kts), then prints, per
# module, the compiler's own counts and every composable that takes an
# *unstable* parameter, with those parameters named.
#
# That list is the part worth reading. Under strong skipping every restartable
# composable is reported skippable, so the flag says little; what decides
# whether one actually skips is an unstable parameter, which is compared by
# instance, arriving as a fresh object on each recomposition. A key composable
# that shows up here taking KeyboardUiState is the regression the key-grid
# design exists to prevent.
#
# Needs JAVA_HOME pointing at a JDK, like any Gradle run.

set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
build=1
modules=()
for arg in "$@"; do
  case "$arg" in
    --no-build) build=0 ;;
    -h|--help) sed -n '2,20p' "$0"; exit 0 ;;
    *) modules+=("${arg%/}") ;;
  esac
done
[ ${#modules[@]} -eq 0 ] && modules=(feature/ime)

task_for() {
  # :app carries the languages dimension on top of capabilities; the library
  # modules only the latter.
  if [ "$1" = app ]; then echo ":app:compileFullIntlDebugKotlin"
  else echo ":${1//\//:}:compileFullDebugKotlin"; fi
}

if [ "$build" = 1 ]; then
  args=()
  for m in "${modules[@]}"; do args+=("$(task_for "$m")" --rerun); done
  (cd "$REPO" && ./gradlew "${args[@]}" -PcomposeMetrics=true --quiet)
fi

for m in "${modules[@]}"; do
  reports="$REPO/$m/build/compose/reports"
  metrics="$REPO/$m/build/compose/metrics"
  if [ ! -d "$reports" ]; then
    echo "$m: no reports under $reports (run without --no-build)" >&2
    continue
  fi
  echo "== $m"
  # One directory per variant under metrics/ (fullDebug/), JSON inside.
  for json in "$metrics"/*/*-module.json "$metrics"/*-module.json; do
    [ -f "$json" ] || continue
    # One flat JSON object; print the counts that matter, in a fixed order.
    for key in totalComposables restartableComposables skippableComposables \
               knownUnstableArguments totalArguments; do
      value="$(grep -o "\"$key\" *: *[0-9]*" "$json" | grep -o '[0-9]*$' || true)"
      [ -n "$value" ] && printf '  %-24s %s\n' "$key" "$value"
    done
  done
  # composables.txt: a signature line per composable, one parameter per line
  # after it, and a line holding only ")" to close it.
  awk '
    /^([a-z]+[ (].* )?fun [A-Za-z0-9_$.]+\(/ {
      # Names come package-qualified; keep the last segment.
      match($0, /fun [A-Za-z0-9_$.]+\(/); name = substr($0, RSTART + 4, RLENGTH - 5)
      sub(/.*\./, "", name)
      unstable = ""; inside = 1; next
    }
    inside && /^\)/ {
      if (unstable != "") printf "  %s:%s\n", name, unstable
      inside = 0; next
    }
    inside && /^  unstable / {
      p = $0; sub(/^  unstable /, "", p); sub(/:.*/, "", p)
      unstable = unstable " " p
    }
  ' "$reports"/*-composables.txt | sort -u | {
    lines="$(cat)"
    if [ -z "$lines" ]; then echo "  no composable takes an unstable parameter"
    else echo "  composables with unstable parameters:"; echo "$lines"; fi
  }
done
