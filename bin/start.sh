#!/usr/bin/env bash
# 启动 OryxOS Web Service（REST API + 管理台 /admin，同一进程）。
# deepseek 等 Provider 凭证从 config/application.yml 读（不需要 export 环境变量）。
# 用法：bin/start.sh [端口]（默认 8080）
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT" # 工作区（.oryxos/、oryxos.db、config/）都相对 CWD，必须在项目根跑

PORT="${1:-8080}"
JAR="$(ls "$ROOT"/oryxos-boot/target/oryxos-boot-*.jar 2>/dev/null | head -1)"
CONFIG="$ROOT/config/application.yml"
PIDFILE="$ROOT/bin/oryxos.pid"
LOG="$ROOT/logs/oryxos.log"

# 1. jar 在不在
if [ -z "$JAR" ]; then
  echo "[ERROR] 找不到可执行 jar，先构建：mvn -pl oryxos-boot -am package -DskipTests"
  exit 1
fi

# 2. 配置在不在——首次从模板生成并提示填 key
if [ ! -f "$CONFIG" ]; then
  cp "$ROOT/config/application.yml.example" "$CONFIG"
  echo "[INFO] 已生成 $CONFIG"
  echo "[ACTION] 请在其中填入 deepseek 的 api-key，然后重跑：bin/start.sh"
  exit 1
fi

# 3. 是否已在运行
if [ -f "$PIDFILE" ] && kill -0 "$(cat "$PIDFILE")" 2>/dev/null; then
  echo "[INFO] OryxOS 已在运行 (pid $(cat "$PIDFILE"))。先 bin/stop.sh 再启动。"
  exit 0
fi

mkdir -p "$ROOT/logs"

# 从外部 yaml 读配置（-D 系统属性，不经 picocli 子命令参数）；serve 同时提供 REST 与 /admin。
# OpenAiAutoConfiguration 已在内置 application.yml 排除，故无需任何环境变量即可启动。
nohup java \
  -Dspring.config.additional-location="optional:file:$ROOT/config/" \
  -jar "$JAR" serve --port "$PORT" \
  > "$LOG" 2>&1 &
PID=$!
echo "$PID" > "$PIDFILE"
echo "[..] OryxOS 启动中 (pid ${PID})，端口 ${PORT}，等待就绪…"

# 等健康检查通过再报成功；进程中途死掉或超时则报失败并贴日志尾（不再谎报 OK）
for _ in $(seq 1 45); do
  if ! kill -0 "$PID" 2>/dev/null; then
    echo "[ERROR] 进程已退出，启动失败。日志尾："
    tail -15 "$LOG"; rm -f "$PIDFILE"; exit 1
  fi
  if curl -fs -o /dev/null "http://localhost:$PORT/api/v1/health" 2>/dev/null; then
    echo "[OK] OryxOS 已就绪 (pid $PID)"
    echo "     REST:   http://localhost:$PORT/api/v1/health"
    echo "     管理台: http://localhost:$PORT/admin/"
    echo "     文档:   http://localhost:$PORT/swagger-ui"
    echo "     日志:   $LOG   （bin/stop.sh 停止）"
    exit 0
  fi
  sleep 1
done
echo "[ERROR] 等待就绪超时（进程仍在，但 /api/v1/health 未通）。日志尾："
tail -15 "$LOG"; exit 1
