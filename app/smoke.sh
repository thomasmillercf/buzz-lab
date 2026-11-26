#!/bin/bash
# On-phone smoke test: installs the build, opens every tab and page the developer hooks reach, and fails on any crash.
# usage: smoke.sh <adb> [apk]   (apk defaults to out/buzzlab.apk; pass - to test what's installed)
# Refuses while a drive is recording, so it never interrupts one.
set -euo pipefail
cd "$(dirname "$0")"
A=$1; APK=${2:-out/buzzlab.apk}
"$A" get-state >/dev/null 2>&1 || { echo "No phone connected"; exit 1; }
if "$A" shell dumpsys notification --noredact 2>/dev/null | grep -q "Recording drive"; then echo "A drive is recording; not touching the phone."; exit 1; fi
[ "$APK" = - ] || "$A" install -r "$APK" >/dev/null
"$A" logcat -c
go() { "$A" shell am start -n uk.buzzlab/.MainActivity "$@" >/dev/null 2>&1; sleep 3; }
go
for t in home analysis driving feel sync home; do go --es tab "$t"; done
newest=$("$A" shell run-as uk.buzzlab ls files/drives 2>/dev/null | tr -d '\r' | sort | tail -1)
[ -z "$newest" ] || { go --es dump "$newest"; sleep 5; }
pid=$("$A" shell pidof uk.buzzlab | tr -d '\r')
crash=$("$A" logcat -d -b crash,main | grep -E "FATAL EXCEPTION|AndroidRuntime.*uk\.buzzlab" || true)
dump=$("$A" logcat -d | grep -o "DUMP $newest.*" || true)
[ -n "$pid" ] && [ -z "$crash" ] || { echo "FAILED: app crashed or isn't running"; echo "$crash" | head -20; exit 1; }
[ -z "$newest" ] || [ -n "$dump" ] || { echo "FAILED: newest drive didn't analyse"; exit 1; }
echo "Smoke test passed: every tab opened without a crash${dump:+; $dump}"
