# Quickstart: 验证 Sandbox 白名单

## 前置

- 已完成第16~22节；`class-24` 分支；`mvn` 可用。
- 无需新增依赖、无需真 LLM/真 webhook（自动化部分全在单测）。

## 自动化验证（harness — mvn test 全绿即通过）

```bash
# 只跑本节沙箱核心回归
mvn test -pl oryxos-tool -am -Dtest=WhitelistSandboxTest

# 四工具拦截回归
mvn test -pl oryxos-tool -am -Dtest='FileToolsTest,ShellToolsTest,HttpToolsTest,NotifyToolsTest'

# 全量门禁（实现完成的定义）
mvn clean verify
```

**预期**：全绿。关键回归：

- `WhitelistSandboxTest` 路径穿越用例——`/allowed/../../etc/passwd` 被拦。
- `WhitelistSandboxTest` 点号边界用例——`*.example.com` 命中 `api.example.com`、拒 `evil-example.com`。
- 四工具各一条——白名单外输入抛 `SandboxViolationException`，底层执行器 `verify(never())`（真正 IO 没发生）。

## 人工项（harness 判不了，见课件"五、做完怎么验"）

1. **真实链路越界留痕**：`application.yml` 配 `shell.allowed_commands: [ls]`，启动 `oryxos chat`，诱导 Agent 跑 `rm ...`，确认被拦且 `tool_invocations` 落一条 success=false 带违规原因。
2. **接口中立性自查**：确认 `WhitelistSandbox` 三 `check*` 方法为 private，`Sandbox` 接口仍只有 `enforce` 一个方法（未被这一档实现带偏）。
3. **配置边界写进文档**：确认三块白名单键（`file.allowed_paths`/`shell.allowed_commands`/`http.allowed_domains`）与"空=deny-all"语义在 application.yml 注释/文档中说明。

## 配置样例（application.yml）

```yaml
file:
  allowed_paths:
    - .oryxos
shell:
  allowed_commands:
    - ls
    - cat
http:
  allowed_domains:
    - "*.example.com"
    - api.deepseek.com
```

> 空列表 = 该类什么都不允许（deny-all）。生产按最小权限收窄。
