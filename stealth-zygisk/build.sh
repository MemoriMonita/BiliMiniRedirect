#!/usr/bin/env bash
# 编译 BiliStealth Zygisk 模块
#
# 坑：火绒会拦截 ld.lld.exe 在 work/ 目录下写 .so（报 Permission denied），
#     但输出到 _android_probe/tmpclang/ 就正常 —— 所以构建目录放在那儿。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
NDK="${ANDROID_NDK:-D:/dsh-home/workspace/_android_probe/sdk/ndk/android-ndk-r27c}"
CLANG="$NDK/toolchains/llvm/prebuilt/windows-x86_64/bin/aarch64-linux-android29-clang++.exe"

# 构建目录（避开火绒拦截区）
BUILD_DIR="D:/dsh-home/workspace/_android_probe/tmpclang"
mkdir -p "$BUILD_DIR"
export TMPDIR="$BUILD_DIR"

SRC="$(cygpath -w "$ROOT/src/stealth.cpp" 2>/dev/null || echo "$ROOT/src/stealth.cpp")"
INC="$(cygpath -w "$ROOT/src" 2>/dev/null || echo "$ROOT/src")"

echo "== 1/2 编译 =="
"$CLANG" -c -fPIC -O2 -std=c++17 -fvisibility=hidden \
  -I "$INC" \
  -o "$BUILD_DIR/stealth.o" \
  "$SRC"

echo "== 1b 编译 plt_hook =="
SRC2="$(cygpath -w "$ROOT/src/plt_hook.cpp" 2>/dev/null || echo "$ROOT/src/plt_hook.cpp")"
"$CLANG" -c -fPIC -O2 -std=c++17 -fvisibility=hidden \
  -I "$INC" \
  -o "$BUILD_DIR/plt_hook.o" \
  "$SRC2"

echo "== 2/2 链接（静态 libc++）=="
"$CLANG" -shared -fPIC -O2 -std=c++17 \
  -static-libstdc++ \
  -Wl,--gc-sections -Wl,-z,max-page-size=16384 -Wl,-soname,arm64-v8a.so \
  -o "$BUILD_DIR/arm64-v8a.so" \
  "$BUILD_DIR/stealth.o" "$BUILD_DIR/plt_hook.o" \
  -llog -ldl -pthread

echo
echo "== 产物 =="
ls -la "$BUILD_DIR/arm64-v8a.so"
"$NDK/toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-readelf.exe" -d "$BUILD_DIR/arm64-v8a.so" | grep NEEDED
echo
echo "OK -> $BUILD_DIR/arm64-v8a.so"
