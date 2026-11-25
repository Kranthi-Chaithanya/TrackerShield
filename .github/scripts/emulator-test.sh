#!/usr/bin/env bash
# Installs TrackerShield on an Android emulator, turns protection on by tapping the real button,
# checks that tracker lookups are blocked while normal sites still work, and checks the per-app
# report using a test app ("Tracker Demo") that contacts trackers on screen and in the background.
# Usage: emulator-test.sh path/to/app-debug.apk path/to/testapp-debug.apk
set -uo pipefail
exec > >(tee emulator-test-log.txt) 2>&1   # keep a copy of everything printed

APK="$1"
DEMO_APK="$2"
PKG=com.trackershield.app
DEMO=com.trackershield.demo
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

raw_dump() {  # raw_dump NAME -> $OUT/ui-NAME.xml
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 && \
    adb pull /sdcard/ui.xml "$OUT/ui-$1.xml" >/dev/null 2>&1
}

# A slow emulator sometimes shows Android's own "<app> isn't responding" pop-up over everything
# (usually for the home screen app). Tap "Wait" so it doesn't hide the screen under test.
dismiss_anr() {  # dismiss_anr NAME -> returns 0 if a pop-up was dismissed
  python3 /tmp/find_on_screen.py "$OUT/ui-$1.xml" contains "isn't responding" >/dev/null || return 1
  local xy
  xy=$(python3 /tmp/find_on_screen.py "$OUT/ui-$1.xml" exact "wait") || return 1
  echo "  (dismissing an Android 'isn't responding' pop-up)"
  adb shell input tap $xy
  sleep 2
  return 0
}

dump_ui() {  # dump_ui NAME -> $OUT/ui-NAME.xml
  for _ in 1 2 3 4 5; do
    if raw_dump "$1"; then
      dismiss_anr "$1" || return 0
    else
      sleep 2
    fi
  done
  return 1
}

on_screen() {  # on_screen MODE TEXT  (MODE = exact|contains)
  dump_ui check && python3 /tmp/find_on_screen.py "$OUT/ui-check.xml" "$1" "$2" >/dev/null
}

expect_on_screen() {  # expect_on_screen MODE TEXT DESCRIPTION
  if on_screen "$1" "$2"; then pass "$3"; else fail "$3 ('$2' not on screen)"; fi
}

wait_for_screen() {  # wait_for_screen MODE TEXT SECONDS
  for _ in $(seq 1 "$3"); do on_screen "$1" "$2" && return 0; sleep 1; done
  return 1
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
adb install -r -g "$DEMO_APK" || { fail "demo install failed"; exit 1; }
adb shell appops set $PKG ACTIVATE_VPN allow      # stands in for tapping OK on the VPN consent dialog
adb shell appops set $PKG GET_USAGE_STATS allow   # stands in for the user turning on "Usage access"
adb logcat -c

echo "== Let the emulator settle after starting up"
sleep 20
adb shell input keyevent KEYCODE_HOME
sleep 3
dump_ui settle || true

echo "== Wait for the emulator's internet connection"
for i in $(seq 1 45); do
  if lookup www.google.com | grep -q "^PING"; then echo "  online"; break; fi
  sleep 2
done

echo "== Before protection: trackers resolve normally"
expect_resolves app-measurement.com
expect_resolves www.google.com

echo "== Open the app"
adb shell am start -W -n $PKG/.MainActivity
sleep 4
shot 1-app-opened
expect_on_screen contains "protection is off" "app shows Protection is OFF"

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

echo "== A real app with trackers: Tracker Demo, first on screen, then in the background"
adb shell am start -W -n $DEMO/.DemoActivity
sleep 10
shot 2-demo-app
adb shell input keyevent KEYCODE_HOME
sleep 13
adb shell am force-stop $DEMO
adb logcat -d -s TrackerDemo | tail -8

echo "== Main screen names the apps"
adb shell am start -W -n $PKG/.MainActivity
sleep 3
shot 3-protection-on
expect_on_screen contains "protection is on" "app shows Protection is ON"
expect_on_screen contains "by tracker demo" "tracker rows name the app that made the lookup"
adb shell dumpsys notification --noredact > "$OUT/notifications.txt" 2>&1
grep -q "tracker lookups blocked" "$OUT/notifications.txt" && pass "status notification shown" || echo "note: notification text not found"

echo "== 'Which apps are tracking me?'"
tap exact "which apps are tracking me?"
if wait_for_screen contains "finished checking" 240; then pass "checked installed apps for built-in tracking code"; else fail "app check did not finish"; fi
sleep 2
shot 4-which-apps
expect_on_screen exact "tracker demo" "Tracker Demo is listed"
expect_on_screen contains "while you weren't using it" "background tracking is reported"
expect_on_screen contains "built-in trackers (4)" "4 built-in trackers found in Tracker Demo"
expect_on_screen exact "shell" "lookups from the command line are named too"

echo "== Tracker Demo details"
tap exact "tracker demo"
sleep 3
shot 5-app-detail
expect_on_screen contains "tried to send data to 5 tracking companies" "detail: companies counted (incl. Sentry)"
expect_on_screen contains "happened while you weren't using the app" "detail: background share shown"
expect_on_screen contains "appsflyer" "detail: AppsFlyer attempts listed"
adb shell input swipe 540 1900 540 500 400
sleep 2
adb shell input swipe 540 1900 540 500 400
sleep 2
shot 6-app-detail-more
expect_on_screen contains "attribution: appsflyer" "detail: AppsFlyer built in (Attribution)"
expect_on_screen contains "analytics: facebook analytics, google firebase analytics" "detail: analytics libraries built in"
expect_on_screen contains "crash reporting: sentry" "detail: Sentry built in (Crash reporting)"
adb shell input keyevent KEYCODE_BACK
sleep 1
adb shell input keyevent KEYCODE_BACK
sleep 2

echo "== Allow one tracker by tapping it"
expect_blocked app-measurement.com   # looked up again, so it is the newest row, at the top of the list
sleep 2
expect_on_screen exact "app-measurement.com" "blocked tracker listed on screen"
tap exact "app-measurement.com"
sleep 1
shot 7-allow-dialog
tap exact "allow"
sleep 2
expect_resolves app-measurement.com
expect_blocked graph.facebook.com

echo "== Tap 'Turn protection off'"
tap exact "turn protection off"
for _ in $(seq 1 10); do vpn_up || break; sleep 1; done
vpn_up && fail "VPN still up after turning off" || pass "VPN stopped"
sleep 2
expect_resolves graph.facebook.com
shot 8-protection-off

echo "== History survives an app restart"
sleep 2
adb shell am force-stop $PKG
adb shell am start -W -n $PKG/.MainActivity
sleep 3
tap exact "which apps are tracking me?"
sleep 4
expect_on_screen exact "tracker demo" "Tracker Demo still listed after restart"
shot 9-after-restart

echo "== Crash check"
adb logcat -d > "$OUT/logcat.txt"
if grep -A20 "FATAL EXCEPTION" "$OUT/logcat.txt" | grep -q "$PKG"; then
  fail "the app crashed (see logcat.txt)"
  grep -A30 "FATAL EXCEPTION" "$OUT/logcat.txt" | head -60
else
  pass "no crashes"
fi
grep -E "TrackerShield|AndroidRuntime" "$OUT/logcat.txt" | grep -v ConnectivityService | head -40 || true

echo
if [ "$FAILS" -eq 0 ]; then echo "ALL EMULATOR CHECKS PASSED"; else echo "$FAILS CHECK(S) FAILED"; fi
exit "$FAILS"
