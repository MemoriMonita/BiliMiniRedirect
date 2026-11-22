#!/usr/bin/env python3
# 打包 BiliStealth Zygisk 模块 zip（KernelSU / Magisk 通用）
import os, zipfile, sys, shutil

ROOT = os.path.dirname(os.path.abspath(__file__))
MOD = os.path.join(ROOT, "module")
TMP = r"D:\dsh-home\workspace\_android_probe\tmpclang"
SO = os.path.join(TMP, "arm64-v8a.so")
OUT = os.path.join(ROOT, "BiliStealth-zygisk-v1.0.zip")

if not os.path.exists(SO):
    print("缺少 .so:", SO); sys.exit(1)

stage = os.path.join(TMP, "stage")
if os.path.exists(stage):
    shutil.rmtree(stage)
os.makedirs(os.path.join(stage, "zygisk"), exist_ok=True)

shutil.copy(os.path.join(MOD, "module.prop"), os.path.join(stage, "module.prop"))
shutil.copy(os.path.join(MOD, "customize.sh"), os.path.join(stage, "customize.sh"))
shutil.copy(SO, os.path.join(stage, "zygisk", "arm64-v8a.so"))

# 不加 META-INF：KernelSU 只认 module.prop，Magisk 的服务脚本在 KSU 上不存在反而可能中止安装。

if os.path.exists(OUT):
    os.remove(OUT)
with zipfile.ZipFile(OUT, "w", zipfile.ZIP_DEFLATED) as z:
    for base, _, files in os.walk(stage):
        for fn in files:
            p = os.path.join(base, fn)
            arc = os.path.relpath(p, stage).replace("\\", "/")
            info = zipfile.ZipInfo(arc)
            info.external_attr = (0o100755 if fn == "customize.sh" else 0o100644) << 16
            info.compress_type = zipfile.ZIP_DEFLATED
            with open(p, "rb") as f:
                z.writestr(info, f.read())

print("OK ->", OUT)
print("大小:", os.path.getsize(OUT), "字节")
with zipfile.ZipFile(OUT) as z:
    for i in z.infolist():
        print(f"  {i.file_size:9d}  {i.filename}")
