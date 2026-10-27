#!/bin/bash
# Hand-rolled Android build for NXT Drive (no Gradle):
#   aapt2 compile/link -> javac -> d8 -> zipalign -> apksigner
# Requires: JDK 17, Android SDK platform 34 + build-tools 34.0.0.
# Set ANDROID_HOME if your SDK is not in ~/android-sdk.
set -euo pipefail
cd "$(dirname "$0")"

ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}"
ANDROID_JAR="$ANDROID_HOME/platforms/android-34/android.jar"
BT="$ANDROID_HOME/build-tools/34.0.0"
OUT=build
# Standard debug keystore location: outside build/, so it survives the
# clean build below and rebuilt APKs install over the previous one.
KEYSTORE="$HOME/.android/debug.keystore"
APK_OUT="$OUT/NXTDrive.apk"

rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/classes"

echo "== aapt2 compile + link"
"$BT/aapt2" compile --dir res -o "$OUT/res.zip"
"$BT/aapt2" link -o "$OUT/unsigned.apk" \
    -I "$ANDROID_JAR" \
    --manifest AndroidManifest.xml \
    --java "$OUT/gen" \
    --min-sdk-version 26 --target-sdk-version 34 \
    --version-code 1 --version-name 1.0 \
    "$OUT/res.zip"

echo "== javac"
javac -source 8 -target 8 -encoding UTF-8 \
    -classpath "$ANDROID_JAR" \
    -d "$OUT/classes" \
    $(find src -name '*.java') "$OUT/gen/com/assix/nxtdrive/R.java"

echo "== d8"
"$BT/d8" --min-api 26 --output "$OUT" $(find "$OUT/classes" -name '*.class')

echo "== add dex + zipalign"
cd "$OUT"
zip -q unsigned.apk classes.dex
cd ..
"$BT/zipalign" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

echo "== debug keystore + sign"
if [ ! -f "$KEYSTORE" ]; then
    mkdir -p "$(dirname "$KEYSTORE")"
    keytool -genkeypair -keystore "$KEYSTORE" -storepass android -keypass android \
        -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
        -dname "CN=Android Debug,O=Android,C=US" 2>/dev/null
fi
"$BT/apksigner" sign --ks "$KEYSTORE" --ks-pass pass:android --key-pass pass:android \
    --out "$APK_OUT" "$OUT/aligned.apk"

echo "== done: $APK_OUT"
"$BT/apksigner" verify "$APK_OUT" && echo "signature OK"
"$BT/aapt2" dump badging "$APK_OUT" | head -3
ls -l "$APK_OUT"
