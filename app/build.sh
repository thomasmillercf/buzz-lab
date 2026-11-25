#!/bin/bash
# usage: build.sh <sdk dir>; makes out/buzzlab.apk
set -e; cd "$(dirname "$0")"; K=$1; export JAVA_HOME=$K/jdk PATH=$K/jdk/bin:$PATH; BT=$(dirname "$(find $K -name aapt2 -type f | head -1)"); AJ=$(find $K -name android.jar | head -1)
rm -rf out && mkdir -p out/c; mkdir -p -m 700 .keys; [ -f .keys/buzzlab.ks ] || keytool -genkeypair -keystore .keys/buzzlab.ks -storepass android -alias d -keyalg RSA -validity 10000 -dname CN=buzzlab >/dev/null 2>&1
$BT/aapt2 compile --dir res -o out/res.zip; $BT/aapt2 link -o out/r.apk out/res.zip -I $AJ --manifest AndroidManifest.xml --java out/gen; javac -nowarn -source 17 -target 17 -cp $AJ -d out/c $(find src out/gen -name '*.java') 2>out/javac.log || { grep -v '^warning' out/javac.log; exit 1; }
$BT/d8 --min-api 29 --lib $AJ --output out $(find out/c -name '*.class'); $BT/aapt2 link -o out/u.apk out/res.zip -I $AJ --manifest AndroidManifest.xml
(cd out && jar uf u.apk classes.dex); $BT/zipalign -f 4 out/u.apk out/a.apk; $BT/apksigner sign --ks .keys/buzzlab.ks --ks-pass pass:android --out out/buzzlab.apk out/a.apk; ls -la out/buzzlab.apk
