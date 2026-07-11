# Quickstart: Tool 体系验证指引

**Date**: 2026-07-11

## 自动化验收

```bash
mvn clean verify        # 全量门禁（完成定义）
mvn test -pl oryxos-tool -am    # 本节 7 个测试类 + 19 节回归
```

关键回归对号：`每个工具的契约三件套都不能缺`（参数化遍历 registry）、`某个MCP_server失联_不能拖垮启动和其他工具`（好 server 在/坏 server 不在/不抛异常）；六个内置工具各"正常跑通+越界被拦"。

## 回归证据

```bash
mvn test    # 全仓（16~19 节 81 个 + 本节新增）
grep -rn "Map.of()" oryxos-cli/src/main/java/io/oryxos/cli/OryxOsRuntime.java   # 预期无命中（空工具表已换 registry）
```

## 人工项（课件"五、怎么用，做完怎么验"）

1. **方式一真跑**：写一份 SKILL.md + `.oryxos/mcp_servers.yaml` 连真实 MCP server（如 github-mcp），chat 里 Agent 能理解意图调用外部工具（需真模型+真 server）。
2. **方式三真跑**：@Tool 示例工具在 `oryxos tool list` 可见、Agent 能调通（tool list 命令 18 节为占位输出，本节后建议人工目检 registry 日志或经 chat 验证）。
3. **真链路审计目检**：chat 里调一次 http_get，查 tool_invocations 表有记录。
4. ⚠️ 24 节前 Sandbox 为 Permissive（每次放行 WARN）——生产不可用状态，Demo 验证专用。
