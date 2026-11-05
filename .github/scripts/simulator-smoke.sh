#!/usr/bin/env bash
# Runs the iPhone and Apple Watch apps in simulators with demo data and screenshots every screen.
# Fails if an app crashes, stops running after launch, or the watch app is not embedded in the iPhone app.
# Expects the simulator build in build/DerivedData (see .github/workflows/apple.yml). Output goes to shots/.
set -uo pipefail

PRODUCTS=build/DerivedData/Build/Products
APP="$PRODUCTS/Debug-iphonesimulator/Hercules.app"
WATCH_APP="$PRODUCTS/Debug-watchsimulator/HerculesWatch.app"
OUT=shots
PHONE_ID=com.hercules.app
WATCH_ID=com.hercules.app.watchkitapp
mkdir -p "$OUT"
FAIL=0
problem() { echo "PROBLEM: $*" | tee -a "$OUT/problems.txt"; FAIL=1; }

# Newest runtime of the kind, preferring a mid-size device.
pick() {
  xcrun simctl list devices available -j | python3 -c '
import json, re, sys
kind = sys.argv[1]
best = None
for runtime, devices in json.load(sys.stdin)["devices"].items():
    m = re.search(r"SimRuntime\.(iOS|watchOS)-(\d+)-(\d+)", runtime)
    if not m or m.group(1) != kind:
        continue
    version = (int(m.group(2)), int(m.group(3)))
    for d in devices:
        name = d["name"]
        if kind == "iOS" and not re.match(r"iPhone \d+$", name):
            continue
        if kind == "watchOS" and not name.startswith("Apple Watch"):
            continue
        preferred = 1 if ("45mm" in name or "46mm" in name or kind == "iOS") else 0
        key = (version, preferred)
        if best is None or key > best[0]:
            best = (key, d["udid"], name, runtime)
if best:
    print(best[1])
    print("%s on %s" % (best[2], best[3].split(".")[-1]), file=sys.stderr)
' "$1"
}

running() { xcrun simctl spawn "$1" launchctl list 2>/dev/null | grep -q "$2"; }

# shot <device> <bundle-id> <file-prefix> <name> <seconds> [launch args...]
shot() {
  local dev=$1 bid=$2 prefix=$3 name=$4 wait=$5
  shift 5
  xcrun simctl terminate "$dev" "$bid" >/dev/null 2>&1 || true
  if ! xcrun simctl launch "$dev" "$bid" "$@" >/dev/null; then
    problem "$prefix-$name: launch failed"
    return
  fi
  sleep "$wait"
  running "$dev" "$bid" || problem "$prefix-$name: not running ${wait}s after launch (crash?)"
  xcrun simctl io "$dev" screenshot "$OUT/$prefix-$name.png" >/dev/null 2>&1 || problem "$prefix-$name: screenshot failed"
  echo "shot $prefix-$name"
}

echo "== bundle"
if [ -d "$APP/Watch/HerculesWatch.app" ]; then echo "watch app embedded"; else problem "watch app is not embedded in Hercules.app/Watch"; fi
plutil -p "$APP/Watch/HerculesWatch.app/Info.plist" 2>/dev/null | grep -E "WKCompanionAppBundleIdentifier|CFBundleIdentifier" || true

echo "== iPhone"
PHONE=$(pick iOS)
[ -n "$PHONE" ] || { problem "no iPhone simulator"; exit 1; }
xcrun simctl boot "$PHONE" 2>/dev/null || true
xcrun simctl bootstatus "$PHONE" -b >/dev/null
xcrun simctl status_bar "$PHONE" override --time 9:41 --batteryState charged --batteryLevel 100 >/dev/null 2>&1 || true
xcrun simctl install "$PHONE" "$APP" || problem "install Hercules.app failed"

shot "$PHONE" $PHONE_ID phone 01-onboarding 7
shot "$PHONE" $PHONE_ID phone 02-today 16 -skipOnboarding YES -demo YES
shot "$PHONE" $PHONE_ID phone 03-today-readout 9 -scroll readout
shot "$PHONE" $PHONE_ID phone 04-today-tips 9 -scroll tips
shot "$PHONE" $PHONE_ID phone 05-today-sleep 9 -scroll sleep
shot "$PHONE" $PHONE_ID phone 06-today-checkin 9 -scroll checkin
shot "$PHONE" $PHONE_ID phone 07-today-trend 9 -scroll trend
shot "$PHONE" $PHONE_ID phone 08-plan 9 -screen plan
shot "$PHONE" $PHONE_ID phone 09-event 10 -screen event
shot "$PHONE" $PHONE_ID phone 10-add 9 -screen add
shot "$PHONE" $PHONE_ID phone 11-data 9 -screen data
shot "$PHONE" $PHONE_ID phone 12-guide 9 -screen guide

echo "== Apple Watch"
WATCH=$(pick watchOS)
if [ -z "$WATCH" ]; then
  problem "no Apple Watch simulator"
else
  xcrun simctl boot "$WATCH" 2>/dev/null || true
  xcrun simctl bootstatus "$WATCH" -b >/dev/null
  xcrun simctl install "$WATCH" "$WATCH_APP" || problem "install HerculesWatch.app failed"
  shot "$WATCH" $WATCH_ID watch 01-permission 10
  shot "$WATCH" $WATCH_ID watch 02-home 20 -demo YES
  shot "$WATCH" $WATCH_ID watch 03-sensors 12 -demo YES -screen sensors
  xcrun simctl spawn "$WATCH" log show --last 15m --style compact \
    --predicate 'subsystem BEGINSWITH "com.hercules"' > "$OUT/watch-log.txt" 2>/dev/null || true
fi

xcrun simctl spawn "$PHONE" log show --last 15m --style compact \
  --predicate 'subsystem BEGINSWITH "com.hercules"' > "$OUT/phone-log.txt" 2>/dev/null || true

echo "== crash reports"
mkdir -p "$OUT/crashes"
find ~/Library/Logs/DiagnosticReports -maxdepth 1 \( -name 'Hercules*' -o -name 'HerculesWatch*' \) -newer "$APP" -exec cp {} "$OUT/crashes/" \; 2>/dev/null
if ls "$OUT/crashes"/* >/dev/null 2>&1; then
  problem "crash reports: $(ls "$OUT/crashes" | tr '\n' ' ')"
  for f in "$OUT/crashes"/*; do head -c 6000 "$f"; echo; done
fi

{
  echo "commit: ${GITHUB_SHA:-local}"
  echo "xcode: $(xcodebuild -version | head -1)"
  echo "problems: $([ $FAIL -eq 0 ] && echo none || cat "$OUT/problems.txt")"
} > "$OUT/summary.txt"
cat "$OUT/summary.txt"
exit $FAIL
