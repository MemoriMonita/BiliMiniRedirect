#!/usr/bin/env bash
# QQ 踢下线监控：持续采样，把关键事件写进日志
# 用法：bash monitor.sh [采样间隔秒] [总时长秒]
set -uo pipefail

INTERVAL="${1:-30}"
DURATION="${2:-86400}"   # 默认 24 小时
LOG="$(cd "$(dirname "$0")" && pwd)/monitor.log"

export MSYS_NO_PATHCONV=1

log() { echo "[$(date '+%Y-%m-%d %H:%M:%S')] $*" >> "$LOG"; }

log "===== 监控启动 interval=${INTERVAL}s duration=${DURATION}s ====="

# 记录基线
log "熄屏超时: $(timeout 15 adb shell settings get system screen_off_timeout 2>/dev/null | tr -d '\r\n ')"

START=$(date +%s)
LAST_KICK=""
LAST_MINI_STATE=""
LAST_SCREEN=""

while true; do
  NOW=$(date +%s)
  ELAPSED=$((NOW - START))
  [ "$ELAPSED" -ge "$DURATION" ] && { log "===== 监控结束（到达时长）====="; break; }

  # 设备还在吗
  if ! timeout 10 adb devices 2>/dev/null | tr -d '\r' | grep -qE "device$"; then
    log "!! 设备断开"
    sleep "$INTERVAL"
    continue
  fi

  # 1) 踢下线事件（增量）
  KICK=$(timeout 20 adb logcat -d -t 400 2>/dev/null | grep -c "KICK_TO_LOGIN" | head -1)
  KICK=${KICK:-0}
  if [ "$KICK" != "$LAST_KICK" ] && [ "$KICK" != "0" ]; then
    DETAIL=$(timeout 20 adb logcat -d -t 400 2>/dev/null | grep "KICK_TO_LOGIN" | tail -1 | cut -c1-120)
    log "!!! 检测到 KICK_TO_LOGIN（近 400 行内 $KICK 条）: $DETAIL"
    LAST_KICK="$KICK"
  fi

  # 2) :mini 进程状态
  MINI=$(timeout 15 adb shell "ps -A 2>/dev/null | grep 'mobileqq:mini'" 2>/dev/null | tr -d '\r' | awk '{print $2}' | tr '\n' ',' | sed 's/,$//')
  MINI_STATE=$([ -z "$MINI" ] && echo "无" || echo "pid=$MINI")
  if [ "$MINI_STATE" != "$LAST_MINI_STATE" ]; then
    log ":mini 进程变化 -> $MINI_STATE"
    LAST_MINI_STATE="$MINI_STATE"
  fi

  # 3) 屏幕状态
  SCREEN=$(timeout 15 adb shell "dumpsys power 2>/dev/null | grep -E 'mWakefulness=' | head -1" 2>/dev/null | tr -d '\r' | sed 's/.*mWakefulness=//' | head -1)
  if [ "$SCREEN" != "$LAST_SCREEN" ]; then
    log "屏幕状态 -> $SCREEN"
    LAST_SCREEN="$SCREEN"
  fi

  # 4) 模块注入情况（增量）
  INJ=$(timeout 20 adb logcat -d -t 400 2>/dev/null | grep -c "BiliRedirect: 注入" | head -1)
  SKIP=$(timeout 20 adb logcat -d -t 400 2>/dev/null | grep -c "BiliRedirect: 跳过" | head -1)
  log "心跳 注入=${INJ:-0} 跳过=${SKIP:-0} mini=$MINI_STATE screen=$SCREEN"

  sleep "$INTERVAL"
done
