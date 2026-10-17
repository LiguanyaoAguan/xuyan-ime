#!/data/data/com.dsharnessmobile.shell/files/usr/bin/bash
# =====================================================================
# 语音输入法 · 免 Gradle 构建（aapt2 + javac + d8 + apksigner）
#
# 关键设计：**先暂存到无空格路径再构建**。
#   本项目位于「DeepSeek harness 专用文件夹」，路径含空格，
#   而 javac @argfile / d8 参数都对空格敏感，原地构建会碎。
#   所以 cp -a 到 $WORK（默认 ~/vim-build）→ 在那里构建 → 产物拷回 dist/
# =====================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
WORK="${WORK:-$HOME/vim-build}"
DIST_OUT="$ROOT/dist"
VER="1.0.0"
APK="Xuyan-${VER}.apk"

export PREFIX="${PREFIX:-/data/data/com.dsharnessmobile.shell/files/usr}"
export PATH="$PREFIX/lib/jvm/java-21-openjdk/bin:$PREFIX/bin:/system/bin"

step(){ echo; echo "===== $* ====="; }

step "0 暂存到无空格工作区"
rm -rf "$WORK"; mkdir -p "$WORK"
cp -a "$ROOT/app" "$ROOT/tools" "$ROOT/jni" "$WORK/"
mkdir -p "$WORK/build" "$WORK/dist" "$DIST_OUT"
cd "$WORK"
echo "  源   $ROOT"
echo "  工作 $WORK"

APP="$WORK/app"; BUILD="$WORK/build"; TOOLS="$WORK/tools"
ANDROID_JAR="$TOOLS/android.jar"
SHERPA_JAR="$TOOLS/sherpa-classes.jar"
KOTLIN_JAR="$TOOLS/kotlin-stdlib.jar"
KEYSTORE="$TOOLS/debug.keystore"
LOG="$BUILD/build-$(date +%Y%m%d_%H%M%S).log"
exec > >(tee -a "$LOG") 2>&1

step "1 环境自检"
for c in javac aapt2 d8 apksigner zip unzip; do
  command -v "$c" >/dev/null || { echo "缺少 $c"; exit 1; }
done
for f in "$ANDROID_JAR" "$SHERPA_JAR" "$KOTLIN_JAR" "$KEYSTORE"; do
  [ -f "$f" ] || { echo "缺少 $f"; exit 1; }
done
echo "  javac $(javac -version 2>&1)"

step "2 aapt2 compile"
aapt2 compile --dir "$APP/res" -o "$BUILD/res.zip"

step "3 aapt2 link"
aapt2 link -o "$BUILD/base.apk" -I "$ANDROID_JAR" \
  --manifest "$APP/AndroidManifest.xml" --java "$BUILD/gen" \
  --min-sdk-version 26 --target-sdk-version 34 \
  --version-code 1 --version-name "$VER" "$BUILD/res.zip"

step "4 javac"
mkdir -p "$BUILD/classes"
find "$APP/src" "$BUILD/gen" -name '*.java' > "$BUILD/sources.txt"
echo "  源文件 $(wc -l < "$BUILD/sources.txt")"
javac -encoding UTF-8 --release 8 \
  -cp "$ANDROID_JAR:$SHERPA_JAR:$KOTLIN_JAR" \
  -d "$BUILD/classes" @"$BUILD/sources.txt" -Xlint:-options
echo "  class $(find "$BUILD/classes" -name '*.class' | wc -l)"

step "5 d8"
mkdir -p "$BUILD/dex"
find "$BUILD/classes" -name '*.class' > "$BUILD/classlist.txt"
d8 --release --min-api 26 --lib "$ANDROID_JAR" --output "$BUILD/dex" \
   $(cat "$BUILD/classlist.txt") "$SHERPA_JAR" "$KOTLIN_JAR"
ls -la "$BUILD/dex/"

step "6 组装 APK"
mkdir -p "$BUILD/pkg/lib/arm64-v8a"
cp -f "$BUILD/base.apk" "$BUILD/unsigned.apk"
cp -f "$BUILD/dex/classes.dex" "$BUILD/pkg/classes.dex"
cp -f "$WORK"/jni/arm64-v8a/*.so "$BUILD/pkg/lib/arm64-v8a/"
( cd "$BUILD/pkg" && zip -q -X -r "$BUILD/unsigned.apk" classes.dex lib )
echo "  so $(unzip -l "$BUILD/unsigned.apk" | grep -c 'arm64-v8a/.*\.so')  dex $(unzip -l "$BUILD/unsigned.apk" | grep -c 'classes.dex')"

step "7 签名"
apksigner sign --ks "$KEYSTORE" \
  --ks-pass pass:android --key-pass pass:android \
  --ks-key-alias androiddebugkey \
  --out "$BUILD/$APK" "$BUILD/unsigned.apk"
apksigner verify --print-certs "$BUILD/$APK" | head -3

step "8 拷回产物"
cp -f "$BUILD/$APK" "$DIST_OUT/$APK"
ls -la "$DIST_OUT/$APK"
echo "SHA256 $(sha256sum "$DIST_OUT/$APK" | cut -d' ' -f1)"
echo; echo "✅ 构建成功 $DIST_OUT/$APK"
