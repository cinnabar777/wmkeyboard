#!/usr/bin/env bash
# Records a Perfetto system trace of the keyboard from an attached device.
#
#   scripts/perfetto.sh                 # 10 s, into build/perfetto/
#   scripts/perfetto.sh 20              # 20 s
#   scripts/perfetto.sh 15 out.pftrace  # 15 s, to a file of your choosing
#
# Start it, type or glide on the phone while it counts down, then open the file
# in https://ui.perfetto.dev (drag it onto the page). The keyboard's own work is
# under com.wasimaster.wmkeyboard as sections named "WM:…" (WM:key, WM:suggest,
# WM:glideDecode, …); see feature/ime/.../ime/ImeTrace.kt for the list and
# config/perfetto/ime.pbtxt for everything else the trace records.
#
# Works on any build, release included: app trace sections are emitted by the
# platform's atrace, not by a debugger, and release builds are already
# <profileable android:shell="true"/>. A debug build traces too, but its timings
# are not the shipped ones (no profile-guided compilation, Compose's debug
# overhead), so judge latency on `fast` or `release`.
#
# Honours ANDROID_SERIAL when more than one device is attached.

set -euo pipefail

SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}"
ADB="${ADB:-$SDK/platform-tools/adb}"
command -v "$ADB" >/dev/null 2>&1 || ADB=adb
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CONFIG="$REPO/config/perfetto/ime.pbtxt"

SECONDS_ARG="${1:-10}"
case "$SECONDS_ARG" in
  ''|*[!0-9]*) echo "usage: $0 [seconds] [output file]" >&2; exit 2 ;;
esac
OUT="${2:-$REPO/build/perfetto/wmkb-$(date +%Y%m%d-%H%M%S).perfetto-trace}"
DEVICE_OUT="/data/misc/perfetto-traces/wmkb.perfetto-trace"

sdk="$("$ADB" shell getprop ro.build.version.sdk | tr -d '\r')"
if [ -z "$sdk" ]; then
  echo "no device answered; is one attached (adb devices)?" >&2
  exit 1
fi
if [ "$sdk" -lt 28 ]; then
  echo "Perfetto needs Android 9 (API 28) or newer; this device is API $sdk." >&2
  echo "Use 'adb shell atrace' there instead (see docs: development/testing)." >&2
  exit 1
fi
if [ "$sdk" -eq 28 ]; then
  # Android 9 ships the tracing daemons switched off.
  "$ADB" shell setprop persist.traced.enable 1
fi

mkdir -p "$(dirname "$OUT")"
echo "Tracing for ${SECONDS_ARG}s on API $sdk. Use the keyboard now."

# The config goes in on stdin: before Android 12 the perfetto binary may only
# read config files from its own directory, and stdin works on every release.
sed "s/^duration_ms: .*/duration_ms: $((SECONDS_ARG * 1000))/" "$CONFIG" \
  | "$ADB" shell perfetto --txt -c - -o "$DEVICE_OUT" >/dev/null

"$ADB" pull "$DEVICE_OUT" "$OUT" >/dev/null
"$ADB" shell rm -f "$DEVICE_OUT" || true

echo "Wrote $OUT"
echo "Open it at https://ui.perfetto.dev. To list the keyboard's slowest sections there,"
echo "run this in the Query (SQL) tab:"
cat <<'SQL'

  select name, count(*) as n,
         round(avg(dur) / 1e6, 2) as avg_ms,
         round(max(dur) / 1e6, 2) as max_ms
  from slice
  where name like 'WM:%'
  group by name
  order by max_ms desc;
SQL
