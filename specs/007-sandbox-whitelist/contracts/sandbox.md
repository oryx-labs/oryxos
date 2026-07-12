# Contract: Sandbox.enforce（白名单语义补全）

接口签名第20节定死，本节不改；这里补全 `WhitelistSandbox` 落地后的行为契约。

## enforce(SandboxAction action)

**前置**：`action` 非 null；`action.target()` 为待校验目标（路径/命令行/URL）。

**后置（放行）**：目标命中对应白名单 → 方法正常返回（void），调用方继续执行真正的 IO。

**后置（拦截）**：目标未命中 → 抛 `SandboxViolationException`，消息含违规原因；**调用方的真正 IO 不发生**（enforce 在 IO 之前）。异常上抛至 `ToolExecutor`，落 `tool_invocations` success=false、error_message=违规原因（复用第17节，不新增审计）。

## 三类校验规则

### FILE_READ / FILE_WRITE → checkFilePath

- 归一：`Path.of(target).normalize().toAbsolutePath()`。
- 命中：归一后路径 `startsWith` 任一 `allowedRoot`（allowedRoot 亦已 normalize）。
- **关键回归**：`/data/agent/../../etc/passwd` normalize 后为 `/etc/passwd`，不在 `/data/agent` 下 → 拒绝（路径穿越被挡）。

### SHELL_COMMAND → checkShellCommand

- 取首 token：`target.trim().split("\\s+")[0]`。
- 命中：`allowedCommands.contains(firstToken)`。
- 例：白名单 `{ls, cat}`，`rm -rf /` 首 token `rm` 不在集 → 拒绝。

### HTTP_REQUEST → checkHttpUrl

- 取 host：`URI.create(target).getHost()`；host 为 null（畸形/无主机）→ 拒绝。
- host 大小写归一后 `anyMatch(matchesDomain)`。
- `matchesDomain(host, pattern)`：
  - `pattern.startsWith("*.")` → `host.endsWith(pattern.substring(1))`（如 `*.example.com` → `host.endsWith(".example.com")`）。
  - 否则 → `host.equals(pattern)`（精确）。
- **关键回归**：
  - `*.example.com` 命中 `api.example.com`、`a.b.example.com`；
  - **不命中** `evil-example.com`（不以 `.example.com` 结尾）——点号边界；
  - **不命中**裸域 `example.com`（同理）。

## 空白名单 = deny-all

任一类白名单为空 → 该类 `anyMatch` 恒 false → 全部拒绝。配置缺失绝不退化为放行（FR-005 / 宪法 VI）。

## 边界（本契约不覆盖）

符号链接解析、chroot 逃逸、容器/microVM 隔离、MCP 子进程与代码执行校验、Profile 级 Tool Policy——全部扩展阶段。
