# Quickstart: Agent 会话发送快捷键验收

验证 [spec.md](./spec.md) 中 FR-001～FR-005 与 SC-002、SC-003。

## 前置条件

- 本地已构建或可运行 OryxOS Web（至少有一个可对话的 Agent，如 `Dorian`）
- 浏览器：Chrome 或 Safari（建议各测一次 modifier 模式下的 Ctrl vs ⌘）

## 启动

```bash
# 仓库根目录，按项目惯用方式启动（示例）
mvn -pl oryxos-boot -am spring-boot:run
```

打开管理台：`http://localhost:8080/admin/` → Agent 列表 → 进入某 Agent → Tab「会话」。

## 场景 A：默认组合键发送（modifier）

1. 若从未设置过，打开 DevTools → Application → Local Storage，确认无键或值为 `modifier`。
2. 输入框输入 `line1`，按 Enter → 应**换行**而非发送。
3. 再输入 `line2`，按 Ctrl+Enter（Mac：⌘+Enter）→ 应发送，输入框清空，对话区出现用户消息。
4. 提示文案应含「Ctrl+Enter」或「⌘+Enter」与「Enter 换行」。

## 场景 B：切换为 Enter 发送

1. 在输入区附近切换到「Enter 发送」。
2. 刷新页面，再次进入同 Agent 会话 → 仍为 Enter 发送（SC-003）。
3. 输入 `hello`，按 Enter → 发送。
4. 输入 `a`，Shift+Enter，再输入 `b`，按 Enter → 一条消息内容为 `a\nb` 或两行后一次发送（以实际 textarea 内容为准：Shift+Enter 后 Enter 发送整段）。

## 场景 C：与按钮一致的限制

1. 空输入框按发送快捷键 → 无请求、无报错。
2. 发送一条较长消息，在「Agent 思考中…」期间再按发送快捷键 → 不重复 POST。

## 场景 D：跨 Agent 全局偏好

1. 设为「Enter 发送」。
2. 切换到另一 Agent 的会话 Tab → 仍为 Enter 发送。

## 构建前端（若只改了 frontend）

```bash
cd oryxos-web/src/main/frontend && npm ci && npm run build
```

或通过 `mvn -pl oryxos-web -am package` 触发 frontend-maven-plugin。

## 预期结果摘要

| 检查项 | 预期 |
|--------|------|
| 快捷键发送 | 与点击「发送」相同 API 与清空输入 |
| 模式切换 | 即时生效 + localStorage 持久化 |
| 后端 | 无新端点、无 Java 变更 |
