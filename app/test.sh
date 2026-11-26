#!/bin/bash
# Runs the JVM tests in test/. usage: test.sh <tools dir>
# Besides what build.sh needs, the tools dir holds test/ with junit-platform-console-standalone-*.jar and json-*.jar (org.json;
# Android's own copy is only a stub off the phone). Builds into out-test, so it never clashes with a build in out.
set -euo pipefail
cd "$(dirname "$0")"
K=$1
OUT=out-test ./build.sh "$K" >/dev/null
export JAVA_HOME=$K/jdk PATH=$K/jdk/bin:$PATH
AJ=$(find "$K" -name android.jar | head -1)
KS=$K/kotlinc/lib/kotlin-stdlib.jar
JU=$(ls "$K"/test/junit-platform-console-standalone-*.jar)
JS=$(ls "$K"/test/json-*.jar)
if [ "${LINT:-format}" = check ]; then "$K/ktlint" "test/**/*.kt"; else "$K/ktlint" --format "test/**/*.kt"; fi
rm -rf out-test/tests
"$K/kotlinc/bin/kotlinc" -nowarn -jvm-target 17 -cp "$JS:$JU:out-test/classes:$AJ" -d out-test/tests $(find test -name '*.kt') 2>&1 | grep -v '^warning' || true
[ -n "$(find out-test/tests -name '*Test.class' -print -quit 2>/dev/null)" ] || { echo "Tests didn't compile"; exit 1; }
java -jar "$JU" execute --disable-banner --details=tree --disable-ansi-colors --fail-if-no-tests \
    -cp "out-test/tests:$JS:out-test/classes:$KS:$AJ" --select-package uk.buzzlab
