# Quickstart: ReAct 循环验证指引

**Date**: 2026-07-10 | **Feature**: [spec.md](./spec.md)

## 前置

- JDK 21、Maven；仓库根目录执行；16 节交付物在位（`mvn -pl oryxos-provider -am test` 绿）。

## 自动化验收（harness 判卷）

```bash
# 全量门禁（完成定义）：单测 + P3C/SpotBugs/FindSecBugs/PMD/Checkstyle/Spotless
mvn clean verify

# 只跑本节六个测试类
mvn test -pl oryxos-core -am -Dtest='ReActLoopTest,PromptBuilderTest,ToolExecutorTest,AgentServiceTest,ContextLoaderTest' -Dsurefire.failIfNoSpecifiedTests=false
mvn test -pl oryxos-storage -am -Dtest='ToolInvocationRepositoryTest' -Dsurefire.failIfNoSpecifiedTests=false
```

预期：全绿。关键回归（课件两个中文名测试，英文方法名 + @DisplayName 对号）：

| @DisplayName（课件原文） | 守点 |
|---|---|
| 模型一直要调工具_转满最大轮数强制停 | `verify(providerService, times(10))` 恰好 10 次 + 答复含"达到最大轮数" |
| 处理中抛异常_ProfileContext也必须被清掉 | assertThrows 后 `assertNull(ProfileContext.current())` |

## 回归证据（跨节契约）

```bash
# 16 节全部测试仍绿（D1 契约上移后的机械替换验证）
mvn test -pl oryxos-provider,oryxos-storage -am
# core 主代码不引用 provider 包（依赖方向验证）
grep -r "io.oryxos.provider" oryxos-core/src/main && echo "FAIL" || echo "OK"
```

## 人工项（课件"五、做完怎么验"，harness 判不了）

1. **Demo 一对话版真模型跑通**：需 18 节 `oryxos chat` 就位后，用真 key 多轮对话验证"查天气→穿搭建议"（本节先由 ReActLoopTest 的替身场景代跑）。
2. **Code review 确认循环自实现**：`ReActLoop.run` 为手写 for 循环，无框架 Agent 封装（测不出来，人眼过）。
