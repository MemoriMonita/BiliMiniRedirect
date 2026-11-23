#!/usr/bin/env bash
# 构建注入探针 APK（只有一行日志，不含 native、不 hook 任何方法）
#
#   bash sysprobe/build-probe.sh
#
# 产物：work/qq-bili-redirect/SysProbe.apk
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"     # work/qq-bili-redirect
P="$ROOT/sysprobe"
SDK="${ANDROID_SDK:-$ROOT/../../_android_probe/sdk}"
BT_VER="${BUILD_TOOLS_VERSION:-36.0.0}"
BT="$SDK/build-tools/$BT_VER"
AJAR="$SDK/platforms/android-36/android.jar"
XAPI="${XPOSED_API:-$ROOT/../../_android_probe/dl/api-82.jar}"

JAVAC="${JAVA_HOME:+$JAVA_HOME/bin/}javac"
KEYSTORE="${KEYSTORE:-$ROOT/keystore.jks}"
KS_PASS="${KEYSTORE_PASS:-biliredirect}"
KS_ALIAS="${KEYSTORE_ALIAS:-biliredirect}"

for f in "$BT/aapt2.exe" "$AJAR" "$XAPI" "$KEYSTORE"; do
  [ -f "$f" ] || { echo "缺少依赖：$f" >&2; exit 1; }
done

OUT="$ROOT/build-probe"
rm -rf "$OUT"
mkdir -p "$OUT/res" "$OUT/gen" "$OUT/classes" "$OUT/dex" "$OUT/bin"

# 火绒会拦 aapt2/zipalign 在 work/ 下写文件，先改名再拷
cp "$BT/aapt2.exe"    "$OUT/bin/_bt_aapt.exe"
cp "$BT/zipalign.exe" "$OUT/bin/_bt_align.exe"
AAPT="$OUT/bin/_bt_aapt.exe"
ALIGN="$OUT/bin/_bt_align.exe"

echo "== 1/6 aapt2 compile =="
"$AAPT" compile --dir "$P/res" -o "$OUT/res.zip"

echo "== 2/6 aapt2 link =="
"$AAPT" link \
  -o "$OUT/base.apk" \
  -I "$AJAR" \
  --manifest "$P/AndroidManifest.xml" \
  -R "$OUT/res.zip" \
  -A "$P/assets" \
  --java "$OUT/gen" \
  --min-sdk-version 29 \
  --target-sdk-version 36 \
  --auto-add-overlay

echo "== 3/6 javac =="
cd "$P"
# 注意：javac/d8 是原生 exe，@文件里一律写「相对 $ROOT 的 Windows 路径」，
# 否则 MSYS 会把 /d/... 转成 \d\... 这种坏路径。
python - "$OUT/sources.txt" <<'PY'
import sys, os
lines = []
for root in ("src", os.path.join("..", "build-probe", "gen")):
    for d, _, fs in os.walk(root):
        for f in fs:
            if f.endswith('.java'):
                lines.append(os.path.relpath(os.path.join(d, f), "..").replace('/', '\\'))
open(sys.argv[1], 'w', encoding='utf-8').write('\n'.join(lines))
print(len(lines), "个 java")
PY
cd "$ROOT"
"$JAVAC" -encoding UTF-8 -source 8 -target 8 -Xlint:-options \
  -bootclasspath "$AJAR" -classpath "$XAPI" \
  -d "$OUT/classes" @"build-probe/sources.txt"

echo "== 4/6 d8 =="
python - "$OUT/classes.txt" <<'PY'
import sys, os
lines = []
for d, _, fs in os.walk("build-probe/classes"):
    for f in fs:
        if f.endswith('.class'):
            lines.append(os.path.join(d, f).replace('/', '\\'))
open(sys.argv[1], 'w', encoding='utf-8').write('\n'.join(lines))
print(len(lines), "个 class")
PY
"$BT/d8.bat" --lib "$AJAR" --classpath "$XAPI" \
  --min-api 29 --output "$OUT/dex" @"build-probe/classes.txt"

echo "== 5/6 打包 + zipalign =="
cp "$OUT/base.apk" "$OUT/unsigned.apk"
python - "$OUT" <<'PY'
import sys, os, zipfile
out = sys.argv[1]
with zipfile.ZipFile(os.path.join(out, "unsigned.apk"), 'a', zipfile.ZIP_DEFLATED) as z:
    z.write(os.path.join(out, "dex", "classes.dex"), "classes.dex")
PY
"$ALIGN" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

echo "== 6/6 签名 =="
"$BT/apksigner.bat" sign --ks "$KEYSTORE" --ks-pass "pass:$KS_PASS" \
  --key-pass "pass:$KS_PASS" --ks-key-alias "$KS_ALIAS" \
  --out "$ROOT/SysProbe.apk" "$OUT/aligned.apk"
"$BT/apksigner.bat" verify "$ROOT/SysProbe.apk" && echo "签名 OK"

echo
echo "OK -> $ROOT/SysProbe.apk"
ls -la "$ROOT/SysProbe.apk"
