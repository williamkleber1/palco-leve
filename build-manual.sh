#!/bin/sh
set -eu

PROJECT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
SDK_DIR=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}}
NDK_DIR=${ANDROID_NDK_ROOT:-$SDK_DIR/ndk/27.2.12479018}
TOOLCHAIN="$NDK_DIR/toolchains/llvm/prebuilt/darwin-x86_64"
BUILD_TOOLS="$SDK_DIR/build-tools/35.0.0"
ANDROID_JAR="$SDK_DIR/platforms/android-35/android.jar"
DEFAULT_JDK="$PROJECT_DIR/../monitor-leve/jdk-package/net.temurin.17.jdk.pkg/Payload/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home"
JDK_DIR=${JAVA_HOME:-$DEFAULT_JDK}
BUILD_DIR="$PROJECT_DIR/manual-build"
OUTPUT_DIR=${OUTPUT_DIR:-$PROJECT_DIR/dist}
KEYSTORE="$PROJECT_DIR/palco-leve.keystore"
export JAVA_HOME="$JDK_DIR"
export PATH="$JDK_DIR/bin:$PATH"

if [ ! -f "$ANDROID_JAR" ] || [ ! -x "$BUILD_TOOLS/aapt2" ]; then
  echo "Android SDK 35 não encontrado. Defina ANDROID_SDK_ROOT ou ANDROID_HOME."
  exit 1
fi

if [ ! -x "$TOOLCHAIN/bin/aarch64-linux-android26-clang++" ]; then
  echo "Android NDK 27.2 não encontrado. Defina ANDROID_NDK_ROOT."
  exit 1
fi

if [ ! -x "$JDK_DIR/bin/javac" ]; then
  echo "JDK 17 não encontrado. Defina JAVA_HOME."
  exit 1
fi

rm -rf "$BUILD_DIR"
mkdir -p "$BUILD_DIR/generated" "$BUILD_DIR/classes" "$BUILD_DIR/dex" \
  "$BUILD_DIR/native/lib/arm64-v8a" "$BUILD_DIR/native/lib/armeabi-v7a" "$OUTPUT_DIR"

COMMON_NATIVE_FLAGS="-std=c++17 -O3 -ffast-math -fPIC -shared -fvisibility=hidden -fno-exceptions -fno-rtti"
NATIVE_SOURCE="$PROJECT_DIR/app/src/main/cpp/native-lib.cpp"
TSF_INCLUDE="$PROJECT_DIR/third_party/TinySoundFont"

"$TOOLCHAIN/bin/aarch64-linux-android26-clang++" \
  $COMMON_NATIVE_FLAGS -static-libstdc++ -I "$TSF_INCLUDE" "$NATIVE_SOURCE" \
  -laaudio -llog -landroid -lm \
  -o "$BUILD_DIR/native/lib/arm64-v8a/libpalcoleve.so"

"$TOOLCHAIN/bin/armv7a-linux-androideabi26-clang++" \
  $COMMON_NATIVE_FLAGS -static-libstdc++ -I "$TSF_INCLUDE" "$NATIVE_SOURCE" \
  -laaudio -llog -landroid -lm \
  -o "$BUILD_DIR/native/lib/armeabi-v7a/libpalcoleve.so"

"$TOOLCHAIN/bin/llvm-strip" --strip-unneeded \
  "$BUILD_DIR/native/lib/arm64-v8a/libpalcoleve.so" \
  "$BUILD_DIR/native/lib/armeabi-v7a/libpalcoleve.so"

"$BUILD_TOOLS/aapt2" compile \
  --dir "$PROJECT_DIR/app/src/main/res" \
  -o "$BUILD_DIR/resources.zip"

"$BUILD_TOOLS/aapt2" link \
  -o "$BUILD_DIR/palco-leve-unsigned.apk" \
  -I "$ANDROID_JAR" \
  --manifest "$PROJECT_DIR/app/src/main/AndroidManifest.xml" \
  --java "$BUILD_DIR/generated" \
  --min-sdk-version 26 \
  --target-sdk-version 29 \
  --version-code 3 \
  --version-name 0.3.0 \
  "$BUILD_DIR/resources.zip"

find "$PROJECT_DIR/app/src/main/java" "$BUILD_DIR/generated" -name '*.java' -print \
  > "$BUILD_DIR/java-sources.txt"

"$JDK_DIR/bin/javac" \
  -source 8 -target 8 \
  -classpath "$ANDROID_JAR" \
  -d "$BUILD_DIR/classes" \
  @"$BUILD_DIR/java-sources.txt"

"$JDK_DIR/bin/jar" cf "$BUILD_DIR/classes.jar" -C "$BUILD_DIR/classes" .
"$BUILD_TOOLS/d8" \
  --lib "$ANDROID_JAR" \
  --min-api 26 \
  --output "$BUILD_DIR/dex" \
  "$BUILD_DIR/classes.jar"

cp "$BUILD_DIR/palco-leve-unsigned.apk" "$BUILD_DIR/palco-leve-with-code.apk"
"$JDK_DIR/bin/jar" uf "$BUILD_DIR/palco-leve-with-code.apk" \
  -C "$BUILD_DIR/dex" classes.dex \
  -C "$BUILD_DIR/native" lib

"$BUILD_TOOLS/zipalign" -f 4 \
  "$BUILD_DIR/palco-leve-with-code.apk" \
  "$BUILD_DIR/palco-leve-aligned.apk"

if [ ! -f "$KEYSTORE" ]; then
  "$JDK_DIR/bin/keytool" -genkeypair \
    -keystore "$KEYSTORE" \
    -storepass palcoleve \
    -keypass palcoleve \
    -alias palcoleve \
    -keyalg RSA \
    -keysize 2048 \
    -validity 10000 \
    -dname "CN=Palco Leve, OU=Local, O=WK, C=BR"
fi

"$BUILD_TOOLS/apksigner" sign \
  --ks "$KEYSTORE" \
  --ks-key-alias palcoleve \
  --ks-pass pass:palcoleve \
  --key-pass pass:palcoleve \
  --out "$OUTPUT_DIR/Palco-Leve-0.3.0.apk" \
  "$BUILD_DIR/palco-leve-aligned.apk"

"$BUILD_TOOLS/apksigner" verify --verbose "$OUTPUT_DIR/Palco-Leve-0.3.0.apk"
ls -lh "$OUTPUT_DIR/Palco-Leve-0.3.0.apk"
