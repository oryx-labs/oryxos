# Quickstart: 知识库（Knowledge Base）验收路径

**Date**: 2026-08-19　**Feature**: [spec.md](spec.md)　**前提**: 仅配置 mock provider（无任何真实 API Key，SC-004）

按用户故事优先级组织的端到端验收脚本。全部命令可在 CI 中复现（mock 向量确定性）。

## A. REST 全流程（US2 + US6：建库 → 传文档 → 绑定 → 对话 → 出处）

```bash
# 0) 启动（mock provider；工作区含 .oryxos/knowledge/ 根目录——init 已建）
oryxos serve --port 8080

# 1) 建库
curl -s -X POST localhost:8080/api/v1/knowledge \
  -H 'Content-Type: application/json' \
  -d '{"name":"ops-manual","description":"运维手册与告警处置知识"}'
# 期望：code=0；.oryxos/knowledge/ops-manual/KNOWLEDGE.md 出现

# 2) 上传文档（两段式：响应立即返回校验结果与 PENDING 状态）
curl -s -X POST localhost:8080/api/v1/knowledge/ops-manual/documents \
  -F 'file=@disk-alert.md'
# 反例校验：上传扫描件 PDF ⇒ 400 + 明确原因（SC-010）

# 3) 轮询索引状态直到 READY（后台虚拟线程推进）
curl -s localhost:8080/api/v1/knowledge/ops-manual/status
# 期望：状态机 PENDING → INDEXING → READY，片段数 > 0

# 4) 绑定 Agent（软连接建立）
curl -s -X PUT localhost:8080/api/v1/agents/default/knowledge/ops-manual
# 验证事实源：readlink .oryxos/agents/default/knowledge/ops-manual
#   ⇒ ../../../knowledge/ops-manual

# 5) 对话验证（US1：命中带出处）
curl -s -X POST localhost:8080/api/v1/agents/default/invoke \
  -H 'Content-Type: application/json' \
  -d '{"message":"服务器磁盘告警怎么处理？"}'
# 期望：回答含出处 [ops-manual] disk-alert.md #<片段位置>；
#       重复执行结果确定可重复（mock 向量，CI 断言）

# 6) 审计核对（US1 场景 6 + FR-022 埋点）
sqlite3 .oryxos/oryxos.db \
  "SELECT tool_name, success FROM tool_invocations ORDER BY id DESC LIMIT 3"
# 期望：retrieve_knowledge 一条，result_json 含 hits 明细/分数/标记/查询原文

# 7) 引用保护（US2 场景 4）：删除被绑定的库 ⇒ 409 + 引用 Agent 清单
curl -s -X DELETE localhost:8080/api/v1/knowledge/ops-manual   # 期望 409
curl -s -X DELETE localhost:8080/api/v1/agents/default/knowledge/ops-manual
curl -s -X DELETE localhost:8080/api/v1/knowledge/ops-manual   # 期望成功
```

## B. GitOps 热加载（US4）

```bash
# 服务运行中，纯文件系统上线一个库
mkdir -p .oryxos/knowledge/faq
printf -- '---\nname: faq\ndescription: 产品FAQ\n---\n' > .oryxos/knowledge/faq/KNOWLEDGE.md
cp product-faq.md .oryxos/knowledge/faq/

# 30 秒内（SC-006）：
oryxos knowledge list          # 可见 faq：名称/描述/后端/文档数/片段数/状态
# 修改文档 ⇒ 指纹变化触发重索引；删除文档 ⇒ 30 秒内不再被命中
# 反例：放入缺清单的目录 ⇒ 不注册 + 可读告警日志，不影响其他库
# 停服期间改目录 ⇒ 启动 reconcile 对账重建（US4 场景 5）
```

## C. 检索质量与降级（US1 场景 2/3/4/5 + Edge Cases）

1. **原文跟读**：构造「片段不足以回答」的提问，观察模型按出处调 `read_file` 读原文
   （本地库目录已自动入白名单，FR-017）。
2. **无关问题**：提问与知识库无关 ⇒ 正常回答、不编造出处。
3. **多库聚合**：绑定两个库后检索 ⇒ 全局 top-K（条数与库数无关），出处可区分来源；
   工具参数限定单库生效；限定未绑定库 ⇒ 可读错误。
4. **零绑定**：未绑定 Agent 上下文零知识注入（对比 system prompt）；误调工具 ⇒
   可读错误（SC-005）。
5. **降级**：把 embedding provider 配置成不可达 ⇒ 检索走关键词路且结果带降级标注、
   对话不中断；此时导入显式失败可重试（FR-013）。

## D. 双缓冲重建（US2 场景 5 / FR-024）

重建期间并发发起检索 ⇒ 旧索引持续命中（无「索引不可用」窗口）；重建完成后命中新
内容；人为制造重建失败（如中途改坏文档）⇒ 旧索引不受影响 + 状态显示失败原因。

## E. 插件契约与能力门禁（US5，远程桩）

注册测试桩后端（仅声明检索能力）并建一个 `backend: stub` 的库：
1. 绑定 + 检索与本地库同一工具、同一出处契约（桩不给出处字段 ⇒ 显式「出处不可用」
   且不可跟读）、同一审计路径——三同逐项核验（SC-011）。
2. 对该库调上传/重建端点 ⇒ 400「该知识库后端不支持此操作」；管理台不渲染对应按钮。
3. 桩模拟不可达 ⇒ 检索可读错误 + 入审计，对话不中断；状态列「不可用」。

## F. 看板（US7）

对同一库制造三类调用（命中 / 零结果 / 降级）后：
`GET /api/v1/knowledge/ops-manual/metrics?from=…&to=…` ⇒ 检索次数、零结果率、
降级率、命中文档分布、出处引用率、零结果查询原文列表；逐项与
`tool_invocations` 手工 SQL 核对一致（SC-009）。

## G. 管理台人工验收（US2/US3/US7）

浏览器 `localhost:8080`：知识库页列表/详情/创建/上传/重建/删除 → Agent 详情页绑定
管理 → 新建 Agent 表单多选知识库 → 「一句话生成」含知识库绑定建议（SC-008 人工
评审）→ 库详情使用看板。管理操作按钮随后端能力集渲染（远程桩库不出上传/重建）。
计时验收 SC-001：建库到首次带出处回答 ≤ 5 分钟、零文件系统接触。
