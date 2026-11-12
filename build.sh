#!/usr/bin/env bash
# 构建 Hyper音量细调 (LSPosed 模块) APK —— 不依赖 Gradle，直接 aapt2 + javac + d8 + apksigner
#
# 依赖（二选一）：
#   1) 本地工具链目录（默认 ../build，可用 TOOLCHAIN=/path 覆盖），需包含：
#        jdk/jdk-*/bin/{java,javac,keytool}
#        bt/android-*/{aapt2,zipalign}、bt/android-*/lib/{d8.jar,apksigner.jar}
#        plat/android-*/android.jar、libxposed-api.jar
#   2) 已安装 Android SDK + JDK17，通过环境变量指定：
#        ANDROID_HOME=/path/to/sdk  BUILD_TOOLS=36.0.0  JAVA_HOME=/path/to/jdk17
#        （libxposed-api.jar 仍需自行放到 TOOLCHAIN 目录下）
#
# 用法：bash build.sh          产物输出到 ../dist/VolumeFine-<版本>.apk
#      OUT_DIR=/tmp/out bash build.sh
set -e

PROJ="$(cd "$(dirname "$0")" && { pwd -W 2>/dev/null || pwd; })"   # Git Bash 下取 D:/... 形式，避免 find 输出反斜杠路径
TOOLCHAIN="${TOOLCHAIN:-$PROJ/../build}"
OUT="${OUT_DIR:-$PROJ/../dist}"
VER_NAME="2.4"
VER_CODE="15"

pick() { for c in "$@"; do [ -x "$c" ] && { echo "$c"; return 0; }; done; return 1; }

# ---- 定位 JDK ----
if [ -n "$JAVA_HOME" ]; then
  JAVA="$JAVA_HOME/bin/java"; JAVAC="$JAVA_HOME/bin/javac"; KEYTOOL="$JAVA_HOME/bin/keytool"
else
  JAVA="$(pick "$TOOLCHAIN"/jdk/*/bin/java "$(command -v java)")"
  JAVAC="$(pick "$TOOLCHAIN"/jdk/*/bin/javac "$(command -v javac)")"
  KEYTOOL="$(pick "$TOOLCHAIN"/jdk/*/bin/keytool "$(command -v keytool)")"
fi

# ---- 定位 Android 构建工具 ----
if [ -n "$ANDROID_HOME" ] && [ -n "$BUILD_TOOLS" ]; then
  BT="$ANDROID_HOME/build-tools/$BUILD_TOOLS"
  AAPT2="$(pick "$BT/aapt2.exe" "$BT/aapt2")"
  ZIPALIGN="$(pick "$BT/zipalign.exe" "$BT/zipalign")"
  APKSIGNER_JAR="$BT/lib/apksigner.jar"; D8_JAR="$BT/lib/d8.jar"
  ANDROID_JAR="$(ls -1 "$ANDROID_HOME"/platforms/android-*/android.jar | sort -V | tail -1)"
else
  BT="$(echo "$TOOLCHAIN"/bt/android-* | awk '{print $1}')"
  AAPT2="$(pick "$BT/aapt2.exe" "$BT/aapt2")"
  ZIPALIGN="$(pick "$BT/zipalign.exe" "$BT/zipalign")"
  D8_JAR="$BT/lib/d8.jar"
  APKSIGNER_JAR="$BT/lib/apksigner.jar"
  ANDROID_JAR="$(echo "$TOOLCHAIN"/plat/android-*/android.jar | awk '{print $1}')"
fi
LIBXPOSED="$TOOLCHAIN/libxposed-api.jar"

for f in "$JAVA" "$JAVAC" "$AAPT2" "$ZIPALIGN" "$D8_JAR" "$APKSIGNER_JAR" "$ANDROID_JAR" "$LIBXPOSED"; do
  [ -e "$f" ] || { echo "缺少构建依赖: $f"; exit 1; }
done

rm -rf "$PROJ/out"
mkdir -p "$PROJ/out/classes" "$PROJ/out/dex" "$PROJ/out/gen" "$OUT"

echo "[1/6] aapt2 compile + link"
"$AAPT2" compile --dir "$PROJ/res" -o "$PROJ/out/res.zip"
"$AAPT2" link -o "$PROJ/out/base.apk" \
  -I "$ANDROID_JAR" \
  --manifest "$PROJ/AndroidManifest.xml" \
  -R "$PROJ/out/res.zip" \
  --java "$PROJ/out/gen" \
  --auto-add-overlay \
  --min-sdk-version 28 --target-sdk-version 36 \
  --version-code "$VER_CODE" --version-name "$VER_NAME"

echo "[2/6] javac"
{ find "$PROJ/src/main/java" -name '*.java'; find "$PROJ/out/gen" -name 'R.java'; } > "$PROJ/out/sources.txt"
"$JAVAC" -nowarn -encoding UTF-8 -source 11 -target 11 \
  -classpath "$ANDROID_JAR;$LIBXPOSED" \
  -d "$PROJ/out/classes" @"$PROJ/out/sources.txt"

echo "[3/6] d8"
find "$PROJ/out/classes" -name '*.class' > "$PROJ/out/classes.txt"
"$JAVA" -cp "$D8_JAR" com.android.tools.r8.D8 \
  --release --min-api 28 --lib "$ANDROID_JAR" \
  --output "$PROJ/out/dex" @"$PROJ/out/classes.txt"

echo "[4/6] package"
python - "$PROJ" <<'PY'
import sys, zipfile, os
proj = sys.argv[1]
out = os.path.join(proj, "out")
base = os.path.join(out, "base.apk")
tmp = os.path.join(out, "unsigned.apk")
src = zipfile.ZipFile(base)
dst = zipfile.ZipFile(tmp, "w", zipfile.ZIP_DEFLATED)
for item in src.infolist():
    dst.writestr(item, src.read(item.filename))
src.close()
dst.write(os.path.join(out, "dex", "classes.dex"), "classes.dex")
meta = os.path.join(proj, "src", "main", "resources", "META-INF", "xposed")
for name in os.listdir(meta):
    dst.write(os.path.join(meta, name), "META-INF/xposed/" + name)
dst.close()
print("packaged", tmp)
PY

echo "[5/6] zipalign"
"$ZIPALIGN" -f -p 4 "$PROJ/out/unsigned.apk" "$PROJ/out/aligned.apk"

echo "[6/6] sign"
KS="${KEYSTORE:-$PROJ/out/debug.keystore}"
if [ ! -f "$KS" ]; then
  "$KEYTOOL" -genkeypair -keystore "$KS" -storepass android \
    -keypass android -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Android Debug,O=Android,C=US" >/dev/null 2>&1
fi
"$JAVA" -jar "$APKSIGNER_JAR" sign \
  --ks "$KS" --ks-pass pass:android --key-pass pass:android \
  --ks-key-alias androiddebugkey --min-sdk-version 28 \
  --out "$OUT/VolumeFine-$VER_NAME.apk" "$PROJ/out/aligned.apk"

"$JAVA" -jar "$APKSIGNER_JAR" verify --min-sdk-version 28 "$OUT/VolumeFine-$VER_NAME.apk"
ls -l "$OUT/VolumeFine-$VER_NAME.apk"
echo "OK"
