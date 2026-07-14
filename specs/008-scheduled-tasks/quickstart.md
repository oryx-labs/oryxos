# Quickstart: 验证定时任务模块

## 前置

- 已完成第16~24节；`class-25` 分支；`mvn` 可用。
- 无需新增依赖、无需真 LLM（harness 全在单测；人工项才需真跑）。

## 自动化验证（harness — mvn test 全绿即通过）

```bash
# 只跑本节调度器 harness
mvn test -pl oryxos-core -am -Dtest=AgentSchedulerTest -Dsurefire.failIfNoSpecifiedTests=false

# 全量门禁（实现完成的定义）
mvn clean verify
```

**预期**：全绿。四个关键回归：

- 注册时 `CronTrigger` 带上配置的 cron **与时区**（ArgumentCaptor 抓 `schedule` 的 Trigger）。
- 锁被占时本次 `runOnce` 直接跳过——`verify(agentService, never()).process(...)`。
- `process` 抛异常时 `runOnce` 不外抛，且再触发一次能进入——`verify(agentService, times(2)).process(...)`（锁真放了）。
- 会话三元组固定 `("scheduler","scheduler",profileName)`、两次触发拿同一 Session。

## 人工项（harness 判不了，见课件"五、做完怎么验"）

1. **真实到点触发一次**：给某 Profile 的 `schedules` 配一条"每分钟"（cron `0 * * * * *` + zone `Asia/Shanghai` + 一句消息），`oryxos serve` 起服务，到点看 Agent 自动发起对话、`llm_calls`/`tool_invocations` 有账。
2. **配置驱动体感**：改 cron 表达式，不重新编译、重启（或重新加载）后按新时间跑。
3. **端到端预演**：完整走"到点自动触发 → 跑完 ReAct 循环 → 留审计"，为 31 节两个定时 Demo 踩地基。
4. **重叠跳过 / 失败隔离 / 锁释放 / 时区 / 会话身份**——已由 harness 覆盖，`mvn test` 绿即打勾。

## 配置样例（Profile YAML 片段）

```yaml
schedules:
  - id: daily-digest
    cron: "0 0 9 * * *"      # 每天 09:00
    zone: Asia/Shanghai       # 显式时区，别让服务器时区做主
    message: 汇总昨天的 PR 评审进度并推送到群
```
