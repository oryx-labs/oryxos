#!/usr/bin/env bash
# 测试 config/application.yml 里 codex provider（中转代理 ai.soulecho.cc）的 key 能否连通。
# 由你本机运行；key 从配置读取、不打印全文（只显示前 6 位核对）。
# 探两个端点：
#   1) /v1/responses        —— 你的 curl 用的形状（input 字段）
#   2) /v1/chat/completions —— OryxOS 运行时用的形状（messages 字段）
# 两个都通，OryxOS 才能直接用这个网关。
#
#   bash scripts/test-codex-key.sh
#   MODEL=gpt-5.5 bash scripts/test-codex-key.sh     # 换模型（默认 gpt-5.5）
set -euo pipefail

CFG="${1:-config/application.yml}"
MODEL="${MODEL:-gpt-5.5}"

[ -f "$CFG" ] || { echo "❌ 找不到配置文件: $CFG"; exit 1; }

# 从 providers 列表里 name: codex 那一块，取 api-key / base-url（去引号、去行尾注释）
read_field() {
  awk -v field="$1" '
    /^[[:space:]]*-[[:space:]]*name:[[:space:]]*codex[[:space:]]*$/ { inblock=1; next }
    /^[[:space:]]*-[[:space:]]*name:/                               { inblock=0 }
    inblock && $0 ~ ("^[[:space:]]*" field ":") {
      line = $0
      sub(/^[[:space:]]*[^:]+:[[:space:]]*/, "", line)
      sub(/[[:space:]]*#.*$/, "", line)
      gsub(/^["'"'"']|["'"'"']$/, "", line)
      print line; exit
    }
  ' "$CFG"
}

KEY="$(read_field 'api-key')"
BASE="$(read_field 'base-url')"; BASE="${BASE%/}"
[ -n "${KEY:-}" ]  || { echo "❌ 没读到 codex 的 api-key"; exit 1; }
[ -n "${BASE:-}" ] || { echo "❌ 没读到 codex 的 base-url"; exit 1; }

echo "provider = codex（中转代理）"
echo "base-url = $BASE"
echo "model    = $MODEL"
echo "api-key  = ${KEY:0:6}…（已隐藏，长度 ${#KEY}）"

probe() {
  local label="$1" path="$2" payload="$3"
  echo
  echo "======== $label  →  $BASE$path ========"
  curl -sS --max-time 30 \
    -w '\n---\nHTTP_CODE=%{http_code}  connect=%{time_connect}s  total=%{time_total}s\n' \
    "$BASE$path" \
    -H "Authorization: Bearer ${KEY}" \
    -H "Content-Type: application/json" \
    -d "$payload" || echo "（curl 失败：网络不通 / 超时）"
}

probe "① Responses API（你的 curl 形状）" "/v1/responses" \
  "{\"model\":\"${MODEL}\",\"input\":\"用一句话打个招呼。\",\"stream\":false}"

probe "② Chat Completions（OryxOS 运行时形状）" "/v1/chat/completions" \
  "{\"model\":\"${MODEL}\",\"messages\":[{\"role\":\"user\",\"content\":\"用一句话打个招呼。\"}]}"

echo
echo "判读："
echo "  • ① 200 有回复            → 网关 + key 通（你的 curl 路径可用）"
echo "  • ② 200 有回复            → OryxOS 能直接用这个网关（AGENT.md 里 provider: codex, model: ${MODEL}）"
echo "  • ② 404 / not found       → 网关只支持 Responses API，OryxOS 的 chat/completions 走不通，需要适配层"
echo "  • 401 invalid key         → 网络通、key 不对/过期"
echo "  • 超时 / curl 失败        → 网络不通"
