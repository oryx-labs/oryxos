#!/usr/bin/env bash
# 停止由 bin/start.sh 启动的 OryxOS（按 pid 文件）。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PIDFILE="$ROOT/bin/oryxos.pid"

if [ ! -f "$PIDFILE" ]; then
  echo "[INFO] 未找到 ${PIDFILE}，OryxOS 可能未通过 start.sh 启动。"
  exit 0
fi

PID="$(cat "$PIDFILE")"
if kill -0 "$PID" 2>/dev/null; then
  kill "$PID"
  # 等它优雅退出，最多 10 秒，仍在则强杀
  for _ in $(seq 1 10); do
    kill -0 "$PID" 2>/dev/null || break
    sleep 1
  done
  if kill -0 "$PID" 2>/dev/null; then
    kill -9 "$PID" 2>/dev/null || true
    echo "[OK] 已强制停止 OryxOS (pid $PID)"
  else
    echo "[OK] 已停止 OryxOS (pid $PID)"
  fi
else
  echo "[INFO] 进程 $PID 不在运行（可能已退出）"
fi
rm -f "$PIDFILE"
