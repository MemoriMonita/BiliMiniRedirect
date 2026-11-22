#!/usr/bin/env bash
# 构建 Bili小程序重定向（LSPosed 模块，内含 native 规避）
#
# 依赖（用环境变量指定，或放在默认位置）：
#   ANDROID_SDK   Android SDK 根目录（build-tools + platforms）
#   ANDROID_NDK   NDK 根目录（编译 libstealth.so）
#   XPOSED_API    Xposed API jar
#   JAVA_HOME     JDK 17+
#
# 坑：火绒会拦截 aapt2/zipalign/ld.lld 在 work/ 目录写文件（Permission denied），
#     所以 native 编译输出放到 _android_probe/tmpclang，可执行文件改名后再用。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
SDK="${ANDROID_SDK:-$ROOT/../../_android_probe/sdk}"
BT_VER="${BUILD_TOOLS_VERSION:-36.0.0}"
BT="$SDK/build-tools/$BT_VER"
AJAR="$SDK/platforms/android-36/android.jar"
XAPI="${XPOSED_API:-$ROOT/../../_android_probe/dl/api-82.jar}"
NDK="${ANDROID_NDK:-$SDK/ndk/android-ndk-r27c}"
CLANG="$NDK/toolchains/llvm/prebuilt/windows-x86_64/bin/aarch64-linux-android29-clang++.exe"

JAVAC="${JAVA_HOME:+$JAVA_HOME/bin/}javac"
KEYTOOL="${JAVA_HOME:+$JAVA_HOME/bin/}keytool"
KEYSTORE="${KEYSTORE:-$ROOT/keystore.jks}"
KS_PASS="${KEYSTORE_PASS:-biliredirect}"
KS_ALIAS="${KEYSTORE_ALIAS:-biliredirect}"

SO_BUILD="D:/dsh-home/workspace/_android_probe/tmpclang"
mkdir -p "$SO_BUILD"
export TMPDIR="$SO_BUILD"

for f in "$BT/aapt2.exe" "$AJAR" "$XAPI"; do
  [ -f "$f" ] || { echo "缺少依赖：$f" >&2; exit 1; }
done

OUT="$ROOT/build"
rm -rf "$OUT"
mkdir -p "$OUT/res" "$OUT/gen" "$OUT/classes" "$OUT/dex" "$OUT/bin" "$OUT/lib/arm64-v8a"

# 改名规避火绒
cp "$BT/aapt2.exe"    "$OUT/bin/_bt_aapt.exe"
cp "$BT/zipalign.exe" "$OUT/bin/_bt_align.exe"
AAPT="$OUT/bin/_bt_aapt.exe"
ALIGN="$OUT/bin/_bt_align.exe"

echo "== 1/8 编译 native（libstealth.so）=="
if [ -f "$CLANG" ]; then
  SRC1="$(cygpath -w "$ROOT/src/stealth_jni.cpp" 2>/dev/null || echo "$ROOT/src/stealth_jni.cpp")"
  SRC2="$(cygpath -w "$ROOT/src/plt_hook.cpp" 2>/dev/null || echo "$ROOT/src/plt_hook.cpp")"
  INC="$(cygpath -w "$ROOT/src" 2>/dev/null || echo "$ROOT/src")"
  "$CLANG" -c -fPIC -O2 -std=c++17 -fvisibility=hidden -I "$INC" \
    -o "$SO_BUILD/stealth_jni.o" "$SRC1"
  "$CLANG" -c -fPIC -O2 -std=c++17 -fvisibility=hidden -I "$INC" \
    -o "$SO_BUILD/plt_hook.o" "$SRC2"
  # 链接输出到 tmpclang（火绒会拦 work/ 下写 .so），再用 python 拷进 build/
  "$CLANG" -shared -fPIC -O2 -std=c++17 -static-libstdc++ \
    -Wl,--gc-sections -Wl,-z,max-page-size=16384 -Wl,-soname,libstealth.so \
    -o "$SO_BUILD/libstealth.so" \
    "$SO_BUILD/stealth_jni.o" "$SO_BUILD/plt_hook.o" \
    -llog -ldl -pthread
  python - "$SO_BUILD/libstealth.so" "$OUT/lib/arm64-v8a/libstealth.so" <<'PY'
import sys, shutil, os
src, dst = sys.argv[1], sys.argv[2]
os.makedirs(os.path.dirname(dst), exist_ok=True)
shutil.copyfile(src, dst)
print("copied", dst, os.path.getsize(dst), "bytes")
PY
  ls -la "$OUT/lib/arm64-v8a/libstealth.so"
else
  echo "!! 没找到 NDK，跳过 native（模块仍可工作，但 QQ 风控不会被屏蔽）"
fi

echo "== 2/8 aapt2 compile =="
"$AAPT" compile --dir "$ROOT/res" -o "$OUT/res.zip"

echo "== 3/8 aapt2 link =="
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

echo "== 4/8 javac =="
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

echo "== 5/8 d8 =="
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

echo "== 6/8 打包 dex + native + zipalign =="
cp "$OUT/base.apk" "$OUT/unsigned.apk"
python - "$OUT" <<'PY'
import sys, os, zipfile
out = sys.argv[1]
apk = os.path.join(out, "unsigned.apk")
with zipfile.ZipFile(apk, 'a', zipfile.ZIP_DEFLATED) as z:
    z.write(os.path.join(out, "dex", "classes.dex"), "classes.dex")
    libdir = os.path.join(out, "lib", "arm64-v8a")
    if os.path.isdir(libdir):
        for fn in os.listdir(libdir):
            z.write(os.path.join(libdir, fn), "lib/arm64-v8a/" + fn)
            print("  打包 lib:", fn)
PY
"$ALIGN" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

echo "== 7/8 签名 =="
if [ ! -f "$KEYSTORE" ]; then
  "$KEYTOOL" -genkeypair -v -keystore "$KEYSTORE" -storepass "$KS_PASS" \
    -keypass "$KS_PASS" -alias "$KS_ALIAS" -keyalg RSA -keysize 2048 \
    -validity 36500 -dname "CN=BiliRedirect, O=CX, C=CN" >/dev/null 2>&1
  echo "已生成 keystore（请自行备份，不要提交）"
fi
"$BT/apksigner.bat" sign --ks "$KEYSTORE" --ks-pass "pass:$KS_PASS" \
  --key-pass "pass:$KS_PASS" --ks-key-alias "$KS_ALIAS" \
  --out "$ROOT/BiliMiniRedirect.apk" "$OUT/aligned.apk"

echo "== 8/8 校验 =="
"$BT/apksigner.bat" verify "$ROOT/BiliMiniRedirect.apk" && echo "签名 OK"
python - "$ROOT/BiliMiniRedirect.apk" <<'PY'
import sys, zipfile
with zipfile.ZipFile(sys.argv[1]) as z:
    for n in z.namelist():
        if n.startswith("lib/") or n == "classes.dex":
            print(f"  {z.getinfo(n).file_size:9d}  {n}")
PY
echo
echo "OK -> $ROOT/BiliMiniRedirect.apk"
ls -la "$ROOT/BiliMiniRedirect.apk"
