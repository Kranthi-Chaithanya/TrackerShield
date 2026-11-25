#!/usr/bin/env bash
# Installs TrackerShield on an Android emulator, turns protection on by tapping the real button,
# and checks that tracker lookups are blocked while normal sites still work.
# Usage: emulator-test.sh path/to/app-debug.apk
set -uo pipefail

APK="$1"
PKG=com.trackershield.app
OUT=emulator-results
mkdir -p "$OUT"
FAILS=0

pass() { echo "PASS: $*"; }
fail() { echo "FAIL: $*"; FAILS=$((FAILS + 1)); }

cat > /tmp/find_on_screen.py <<'PY'
import re, sys, xml.etree.ElementTree as ET
path, mode, want = sys.argv[1], sys.argv[2], sys.argv[3].lower()
root = ET.parse(path).getroot()
for n in root.iter('node'):
    t = (n.get('text') or '').strip().lower()
    if (mode == 'exact' and t == want) or (mode == 'contains' and want in t):
        x1, y1, x2, y2 = map(int, re.findall(r'\d+', n.get('bounds')))
        print((x1 + x2) // 2, (y1 + y2) // 2)
        sys.exit(0)
sys.exit(1)
PY

dump_ui() {  # dump_ui NAME -> $OUT/ui-NAME.xml
  for _ in 1 2 3; do
    adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 && \
      adb pull /sdcard/ui.xml "$OUT/ui-$1.xml" >/dev/null 2>&1 && return 0
    sleep 2
  done
  return 1
}

on_screen() {  # on_screen MODE TEXT  (MODE = exact|contains)
  dump_ui check && python3 /tmp/find_on_screen.py "$OUT/ui-check.xml" "$1" "$2" >/dev/null
}

tap() {  # tap MODE TEXT
  dump_ui tap || { fail "could not read the screen"; return 1; }
  local xy
  xy=$(python3 /tmp/find_on_screen.py "$OUT/ui-tap.xml" "$1" "$2") || { fail "'$2' not found on screen"; return 1; }
  adb shell input tap $xy
  sleep 1
}

shot() { adb exec-out screencap -p > "$OUT/$1.png"; }

# First line of ping output: shows the resolved address, or "unknown host" when blocked.
lookup() { adb shell "ping -c 1 -W 2 $1" 2>&1 | head -1 | tr -d '\r'; }

expect_blocked() {
  local r; r=$(lookup "$1"); echo "  $1 -> $r"
  if echo "$r" | grep -qi "unknown host"; then pass "$1 blocked"; else fail "$1 was NOT blocked"; fi
}

expect_resolves() {
  local r; r=$(lookup "$1"); echo "  $1 -> $r"
  if echo "$r" | grep -q "^PING"; then pass "$1 resolves"; else fail "$1 did not resolve"; fi
}

vpn_up() { adb shell ip -4 addr show tun0 2>/dev/null | grep -q "10.111.222.1"; }

echo "== Install"
adb wait-for-device
adb shell settings put global window_animation_scale 0 || true
adb install -r -g "$APK" || { fail "install failed"; exit 1; }
adb shell appops set $PKG ACTIVATE_VPN allow   # stands in for tapping OK on the VPN consent dialog
adb logcat -c

echo "== Before protection: trackers resolve normally"
expect_resolves app-measurement.com
expect_resolves www.google.com

echo "== Open the app"
adb shell am start -W -n $PKG/.MainActivity
sleep 4
shot 1-app-opened
on_screen contains "protection is off" && pass "app shows Protection is OFF" || fail "app screen not shown"

echo "== Tap 'Turn protection on'"
tap exact "turn protection on"
for _ in $(seq 1 15); do vpn_up && break; sleep 1; done
vpn_up && pass "VPN interface tun0 is up with 10.111.222.1" || fail "VPN did not start"
adb shell dumpsys activity services $PKG > "$OUT/service.txt" 2>&1
grep -q "isForeground=true" "$OUT/service.txt" && pass "service runs in the foreground" || echo "note: foreground flag not found"

echo "== With protection: trackers blocked, normal sites work"
expect_blocked app-measurement.com
expect_blocked region1.app-measurement.com
expect_blocked graph.facebook.com
expect_blocked googleads.g.doubleclick.net
expect_blocked api2.amplitude.com
expect_resolves www.google.com
expect_resolves www.wikipedia.org
expect_resolves fcm.googleapis.com

sleep 2
shot 2-protection-on
on_screen contains "protection is on" && pass "app shows Protection is ON" || fail "app does not show ON"
on_screen exact "app-measurement.com" && pass "blocked tracker listed on screen" || fail "tracker missing from list"
adb shell dumpsys notification --noredact > "$OUT/notifications.txt" 2>&1
grep -q "tracker lookups blocked" "$OUT/notifications.txt" && pass "status notification shown" || echo "note: notification text not found"

echo "== Allow one tracker by tapping it"
tap exact "app-measurement.com"
sleep 1
shot 3-allow-dialog
tap exact "allow"
sleep 2
expect_resolves app-measurement.com
expect_blocked graph.facebook.com
shot 4-after-allow

echo "== Tap 'Turn protection off'"
tap exact "turn protection off"
for _ in $(seq 1 10); do vpn_up || break; sleep 1; done
vpn_up && fail "VPN still up after turning off" || pass "VPN stopped"
sleep 2
expect_resolves graph.facebook.com
shot 5-protection-off

echo "== Crash check"
adb logcat -d > "$OUT/logcat.txt"
if grep -A20 "FATAL EXCEPTION" "$OUT/logcat.txt" | grep -q "$PKG"; then
  fail "the app crashed (see logcat.txt)"
  grep -A30 "FATAL EXCEPTION" "$OUT/logcat.txt" | head -60
else
  pass "no crashes"
fi
grep "TrackerShield" "$OUT/logcat.txt" | head -40 || true

echo
if [ "$FAILS" -eq 0 ]; then echo "ALL EMULATOR CHECKS PASSED"; else echo "$FAILS CHECK(S) FAILED"; fi
exit "$FAILS"
