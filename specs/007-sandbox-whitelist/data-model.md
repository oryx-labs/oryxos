# Data Model: Sandbox 白名单

本节无持久化实体、无表变更。以下是内存中的值对象与配置载体（均落 `oryxos-tool`）。

## 既有（第20节交付，本节不改签名）

### Sandbox（接口）

```java
public interface Sandbox {
  void enforce(SandboxAction action);  // 不过抛 SandboxViolationException
}
```

### SandboxAction（record）

```java
public record SandboxAction(ActionType type, String target) {}
```

- `target`：文件路径 / 完整命令行 / 完整 URL（按 type 而定）。

### ActionType（enum，四值）

```java
public enum ActionType { FILE_READ, FILE_WRITE, SHELL_COMMAND, HTTP_REQUEST }
```

### SandboxViolationException

`extends RuntimeException`；消息即违规原因（进审计的 error_message）。

## 新增：WhitelistSandbox（实现类）

| 字段 | 类型 | 来源 | 说明 |
|------|------|------|------|
| `allowedRoots` | `List<Path>` | `FileSandboxProperties.allowedPaths()` `.map(Path::of).map(Path::normalize)` | 允许的文件根目录（构造期归一） |
| `allowedCommands` | `Set<String>` | `ShellSandboxProperties.allowedCommands()` `Set.copyOf` | 允许的命令首 token |
| `allowedDomainPatterns` | `List<String>` | `HttpSandboxProperties.allowedDomains()` `List.copyOf` | 允许的域名（含 `*.` 通配） |

**行为（enforce）**：传统 switch on `action.type()`：

- `FILE_READ` / `FILE_WRITE` → `checkFilePath(target)`
- `SHELL_COMMAND` → `checkShellCommand(target)`
- `HTTP_REQUEST` → `checkHttpUrl(target)`

三 `check*` 与 `matchesDomain` 均 `private`（契约只暴露 enforce）。校验规则见 research.md Decision 2。空白名单 → `anyMatch` 恒 false → deny-all。

## 新增：三块配置 record（@ConfigurationProperties）

```java
@ConfigurationProperties(prefix = "file")
public record FileSandboxProperties(List<String> allowedPaths) {}

@ConfigurationProperties(prefix = "shell")
public record ShellSandboxProperties(List<String> allowedCommands) {}

@ConfigurationProperties(prefix = "http")
public record HttpSandboxProperties(List<String> allowedDomains) {}
```

- 绑定键：`file.allowed_paths`、`shell.allowed_commands`、`http.allowed_domains`（relaxed binding：`allowed-paths`/`allowed_paths` 皆可）。
- `null` 列表在构造 `WhitelistSandbox` 时归一为空列表（deny-all），不 NPE。

## 改造：NotifyTools（第19节交付，预告改造点）

| 变更 | 前 | 后 |
|------|----|----|
| 构造 | `NotifyTools(Map<String,NotifyChannelAdapter>)` | `NotifyTools(Map<String,NotifyChannelAdapter>, Sandbox)` |
| execute | `adapter.send(...)` 直接调 | send 前 `sandbox.enforce(new SandboxAction(HTTP_REQUEST, url))` |

- `url` 来源：`resolved.config().get("url")`（渠道配置里的推送地址）。
- enforce 抛异常不 catch，上抛至 ToolExecutor → 失败审计 success=false（复用第17节路径）。
