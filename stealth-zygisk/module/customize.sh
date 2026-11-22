#!/system/bin/sh
# BiliStealth 安装脚本（KernelSU / Magisk 通用）
# KernelSU 只要求 module.prop，customize.sh 可选；这里只做权限设置和提示。

ui_print "- 安装 BiliStealth"
ui_print "- 作用：屏蔽 QQ 风控库 libfekit.so 对 /proc/self/smaps 的读取"

if [ -d "$MODPATH/zygisk" ]; then
  chmod 755 "$MODPATH/zygisk" 2>/dev/null
  for f in "$MODPATH/zygisk"/*.so; do
    [ -f "$f" ] && chmod 644 "$f"
  done
  ui_print "- Zygisk 载荷:"
  ls "$MODPATH/zygisk" 2>/dev/null | while read -r f; do
    ui_print "  $f"
  done
else
  ui_print "!! 警告: 缺少 zygisk 目录，模块不会生效"
fi

ui_print "- 安装完成，请完整重启设备（不是软重启）"
