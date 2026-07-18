# Quickstart / 验收指南：动态管理 Agent

前置：JDK 21、已构建。凭证走环境变量。本节单测无需 key（provider/store/scheduler/loader 全 mock）；真链路（建/传/丢即上线、cron 真触发）留 31 节人工项。

## 自动化门禁（实现完成的定义）

```bash
mvn clean verify        # 期望 BUILD SUCCESS，全绿（含 P3C/SpotBugs/FindSecBugs/PMD）
```

## 只跑本节 harness

```bash
mvn -pl oryxos-boot -am clean test -Dsurefire.failIfNoSpecifiedTests=false \
  -Dtest='AgentLifecycleServiceTest,WorkspaceWatcherTest,WorkspaceApiControllerTest,GenerateTest,AgentApiControllerTest'
```

## 关键回归点 ↔ 验收场景

| 验收点（spec SC/FR） | 怎么验（harness） |
|---|---|
| 注册失败回滚不留半个（SC-003/FR-003） | `AgentLifecycleServiceTest`：`doThrow` 注入 register 失败 → verify `agentStore.delete`、`registerProfile` never、`exists` false |
| 删除时序（SC-004/FR-007） | `AgentLifecycleServiceTest`：`InOrder(agentScheduler, profileRegistry, agentStore)` = unregisterProfile → remove → archive |
| 丢目录即上线（SC-002/FR-002） | `WorkspaceWatcherTest`：`handleChange(dir, ENTRY_CREATE)` → `lifecycle.register` 被调、Agent 进 `ProfileRegistry` |
| 防目录穿越（SC-006/FR-008） | `WorkspaceApiControllerTest`：`file?path=../../etc/passwd` → 400；合法 path → 内容 |
| 生成不落盘不注册（SC-005/FR-004） | `GenerateTest`：generate 后 `agentStore` 无写、`profileRegistry` 无变化；产出可被 `AgentMarkdown+deriveProfile` 解析；非法→400 |
| 三录入同一段 register（FR-009） | `AgentLifecycleServiceTest` + `WorkspaceWatcherTest`：create 与 watcher 都落到 `register(agentDir)` |
| 端点错误码（FR-010） | `AgentApiControllerTest`（standalone MockMvc）：冲突→400、不存在→404、统一 `ApiResponse` |

## 手动路径（真链路，31 节 / 人工）

```bash
# 建 Agent 即上线
curl -X POST localhost:8080/api/v1/agents -d '{"name":"demo","agentMarkdown":"---\nname: demo\nprovider:\n  name: mock\n  model: mock-model\n---\n你好"}'
curl localhost:8080/api/v1/agents            # demo 立刻可见，未重启
# 丢目录即上线
mkdir -p .oryxos/agents/demo2 && printf -- '---\nname: demo2\nprovider:\n  name: mock\n  model: mock-model\n---\nhi' > .oryxos/agents/demo2/AGENT.md
sleep 2 && curl localhost:8080/api/v1/agents # demo2 几秒内出现
# 一句话生成
curl -X POST localhost:8080/api/v1/agents/generate -d '{"sentence":"每天早上九点查北京天气推送到群"}'
# 文件浏览器防穿越
curl 'localhost:8080/api/v1/workspace/file?path=../../etc/passwd'   # 400
```

## 前序回归（跨节契约）

```bash
mvn -pl oryxos-core -am test   # 29 节 AgentLoader/ProfileRegistry/AgentScheduler、26 节 web 测试全绿
```
