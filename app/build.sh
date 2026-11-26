#!/bin/bash
# Builds out/buzzlab.apk. usage: [OUT=folder] build.sh <tools dir>
# The tools dir holds jdk/, kotlinc/, ktlint (github.com/pinterest/ktlint), and an Android platform (android.jar) and build-tools (aapt2, d8, zipalign, apksigner).
set -euo pipefail
cd "$(dirname "$0")"
K=$1
export JAVA_HOME=$K/jdk PATH=$K/jdk/bin:$PATH
BT=$(dirname "$(find "$K" -name aapt2 -type f | head -1)")
AJ=$(find "$K" -name android.jar | head -1)
KS=$K/kotlinc/lib/kotlin-stdlib.jar
O=${OUT:-out} # OUT=out-test builds in another folder, so a test run never clashes with a build
rm -rf $O && mkdir -p $O/classes

# Kotlin style (.editorconfig): fix what can be fixed, then fail on anything left. LINT=check only checks, changing nothing.
if [ "${LINT:-format}" = check ]; then "$K/ktlint" "src/**/*.kt"; else "$K/ktlint" --format "src/**/*.kt"; fi

# Signing key: made once and kept. Updates must use the same key, or Android makes you uninstall first.
mkdir -p -m 700 .keys
[ -f .keys/buzzlab.ks ] || keytool -genkeypair -keystore .keys/buzzlab.ks -storepass android -alias d -keyalg RSA -validity 10000 -dname CN=buzzlab >/dev/null 2>&1

# Resources, the R class, and keep rules for the parts Android starts by name
$BT/aapt2 compile --dir res -o $O/res.zip
assets=(); [ -d assets ] && assets=(-A assets)
$BT/aapt2 link -o $O/base.apk $O/res.zip -I "$AJ" --manifest AndroidManifest.xml "${assets[@]}" --java $O/gen --proguard $O/keep.pro

# Kotlin first (it can see the Java sources), then Java against the Kotlin classes
kt=$(find src -name '*.kt'); java=$(find src $O/gen -name '*.java')
[ -z "$kt" ] || $K/kotlinc/bin/kotlinc -nowarn -jvm-target 17 -cp "$AJ" -d $O/classes $kt $java 2>&1 | grep -v '^warning' || true
[ -z "$kt" ] || [ -n "$(find $O/classes -name '*.class' -print -quit)" ] || { echo "Kotlin failed"; exit 1; }
javac -nowarn -source 17 -target 17 -cp "$AJ:$O/classes:$KS" -d $O/classes $java 2>$O/javac.log || { grep -v '^warning' $O/javac.log; exit 1; }

# R8 shrinks the Kotlin library down to what the app uses. Names stay readable for crash logs.
printf -- '-dontobfuscate\n-keepattributes SourceFile,LineNumberTable\n-dontwarn org.jetbrains.annotations.**\n' > $O/rules.pro
jar cf $O/app.jar -C $O/classes .
java -cp "$BT/lib/d8.jar" com.android.tools.r8.R8 --release --min-api 29 --lib "$AJ" --pg-conf $O/keep.pro --pg-conf $O/rules.pro --output $O $O/app.jar "$KS" 2>&1 | grep -vE "^Warning|^$" || true
[ -f $O/classes.dex ] || { echo "R8 failed"; exit 1; }

cp $O/base.apk $O/unsigned.apk
(cd $O && jar uf unsigned.apk classes.dex)
$BT/zipalign -f 4 $O/unsigned.apk $O/aligned.apk
$BT/apksigner sign --ks .keys/buzzlab.ks --ks-pass pass:android --out $O/buzzlab.apk $O/aligned.apk
ls -la $O/buzzlab.apk
