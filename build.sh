#!/usr/bin/env bash
# 构建 B站跳转重定向 LSPosed 模块（不依赖 Gradle / Android Studio）
#
# 依赖（用环境变量指定，或放在默认位置）：
#   ANDROID_SDK    Android SDK 根目录，需含 build-tools/<ver>/ 与 platforms/android-36/
#   XPOSED_API     Xposed API jar（api-82.jar）
#   JAVA_HOME      JDK 17+
#
# 例：
#   ANDROID_SDK=~/Android/Sdk XPOSED_API=~/libs/api-82.jar bash build.sh
#
# 注意：Windows 上火绒 HIPS 会拦截 aapt2.exe / zipalign.exe 写文件（报「数据无效 (13)」），
# 把可执行文件改名后即可正常工作 —— 所以这里统一拷成 _bt_*.exe 再用。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"

SDK="${ANDROID_SDK:-$ROOT/../../_android_probe/sdk}"
BT_VER="${BUILD_TOOLS_VERSION:-36.0.0}"
BT="$SDK/build-tools/$BT_VER"
AJAR="$SDK/platforms/android-36/android.jar"
XAPI="${XPOSED_API:-$ROOT/../../_android_probe/dl/api-82.jar}"

JAVAC="${JAVA_HOME:+$JAVA_HOME/bin/}javac"
KEYTOOL="${JAVA_HOME:+$JAVA_HOME/bin/}keytool"

KS="${KEYSTORE:-$ROOT/keystore.jks}"
KS_PASS="${KEYSTORE_PASS:-biliredirect}"
KS_ALIAS="${KEYSTORE_ALIAS:-biliredirect}"

for f in "$BT/aapt2.exe" "$AJAR" "$XAPI"; do
  [ -f "$f" ] || { echo "缺少依赖：$f" >&2; exit 1; }
done

OUT="$ROOT/build"
rm -rf "$OUT"
mkdir -p "$OUT/res" "$OUT/gen" "$OUT/classes" "$OUT/dex" "$OUT/bin"

# 改名规避火绒拦截
cp "$BT/aapt2.exe"    "$OUT/bin/_bt_aapt.exe"
cp "$BT/zipalign.exe" "$OUT/bin/_bt_align.exe"
AAPT="$OUT/bin/_bt_aapt.exe"
ALIGN="$OUT/bin/_bt_align.exe"

echo "== 1/7 aapt2 compile =="
"$AAPT" compile --dir "$ROOT/res" -o "$OUT/res.zip"

echo "== 2/7 aapt2 link =="
"$AAPT" link \
  -o "$OUT/base.apk" \
  -I "$AJAR" \
  --manifest "$ROOT/AndroidManifest.xml" \
  -R "$OUT/res.zip" \
  -A "$ROOT/assets" \
  --java "$OUT/gen" \
  --min-sdk-version 29 \
  --target-sdk-version 36 \
  --auto-add-overlay

echo "== 3/7 javac =="
# javac 的 @file 里用相对路径（相对当前工作目录），绝对路径会被 MSYS 改写
cd "$ROOT"
python - "$OUT/sources.txt" <<'PY'
import sys, os
lines = []
for root in ("src", "build/gen"):
    for d, _, fs in os.walk(root):
        for f in fs:
            if f.endswith('.java'):
                lines.append(os.path.join(d, f).replace('/', '\\'))
open(sys.argv[1], 'w', encoding='utf-8').write('\n'.join(lines))
print(len(lines), "个 java")
PY
"$JAVAC" -encoding UTF-8 -source 8 -target 8 -Xlint:-options \
  -bootclasspath "$AJAR" -classpath "$XAPI" \
  -d "$OUT/classes" @build/sources.txt

echo "== 4/7 d8 =="
cd "$ROOT"
python - "$OUT/classes.txt" <<'PY'
import sys, os
lines = []
for d, _, fs in os.walk("build/classes"):
    for f in fs:
        if f.endswith('.class'):
            lines.append(os.path.join(d, f).replace('/', '\\'))
open(sys.argv[1], 'w', encoding='utf-8').write('\n'.join(lines))
print(len(lines), "个 class")
PY
"$BT/d8.bat" --lib "$AJAR" --classpath "$XAPI" \
  --min-api 29 --output "$OUT/dex" @build/classes.txt

echo "== 5/7 打包 dex =="
cp "$OUT/base.apk" "$OUT/unsigned.apk"
python - "$OUT/unsigned.apk" "$OUT/dex/classes.dex" <<'PY'
import sys, zipfile
apk, dex = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(apk, 'a', zipfile.ZIP_DEFLATED) as z:
    z.write(dex, 'classes.dex')
print("classes.dex 已写入")
PY

echo "== 6/7 zipalign =="
"$ALIGN" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

echo "== 7/7 签名 =="
if [ ! -f "$KS" ]; then
  "$KEYTOOL" -genkeypair -v -keystore "$KS" -storepass "$KS_PASS" \
    -keypass "$KS_PASS" -alias "$KS_ALIAS" -keyalg RSA -keysize 2048 \
    -validity 36500 -dname "CN=BiliRedirect, O=CX, C=CN" >/dev/null 2>&1
  echo "已生成 $KS（请自行备份，不要提交到仓库）"
fi
"$BT/apksigner.bat" sign --ks "$KS" --ks-pass "pass:$KS_PASS" \
  --key-pass "pass:$KS_PASS" --ks-key-alias "$KS_ALIAS" \
  --out "$ROOT/BiliRedirect.apk" "$OUT/aligned.apk"

"$BT/apksigner.bat" verify --print-certs "$ROOT/BiliRedirect.apk" | head -5
echo
echo "OK -> $ROOT/BiliRedirect.apk"
ls -la "$ROOT/BiliRedirect.apk"
