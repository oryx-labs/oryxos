# Research: Sandbox 白名单实现

本节无 NEEDS CLARIFICATION（实现细节逐字对齐第24节课件 §3.2；两处通配符语义已在 spec Clarifications 从课件定夺）。以下记录关键实现决策与其依据。

## Decision 1：ActionType 保留四值，反向同步文档

- **Decision**: 保留第20节已交付的 `ActionType` 四值（`FILE_READ`/`FILE_WRITE`/`SHELL_COMMAND`/`HTTP_REQUEST`）；`WhitelistSandbox.enforce` 的 switch 把 `FILE_READ`、`FILE_WRITE` 两 case 都路由到 `checkFilePath`。反向修改 `docs/TechnicalSolution.md §6.7` 与第24节课件 §3.1 的三值示例（`FILE_ACCESS`/`SHELL_EXEC`/`HTTP_REQUEST`）为四值。
- **Rationale**: 代码是已交付的既成事实（第20节 FileTools/HttpTools/ShellTools 的 enforce 调用已按四值接线，grep 实证）；文档改动成本远低于改代码 + 回归四工具。停点已确认。
- **Alternatives considered**: 把代码改回三值——被否，会破坏第20节四处 enforce 调用点与其测试，且 `FILE_READ`/`FILE_WRITE` 区分对未来"读写分权限"有价值。

## Decision 2：校验实现逐字对齐课件 §3.2

- **Decision**:
  - `checkFilePath(raw)`：`Path.of(raw).normalize().toAbsolutePath()` → `allowedRoots.stream().anyMatch(target::startsWith)`，不过抛 `SandboxViolationException`。`allowedRoots` 由配置路径 `.map(Path::of).map(Path::normalize)`（构造期归一）。
  - `checkShellCommand(cmd)`：取首 token（`cmd.trim().split("\\s+")[0]`）→ `allowedCommands.contains(firstToken)`，不过抛异常。
  - `checkHttpUrl(url)`：`URI.create(url).getHost()` 取 host（大小写归一）→ `allowedDomainPatterns.anyMatch(p -> matchesDomain(host, p))`，host 为 null（畸形/无主机）判不过。
  - `matchesDomain(host, pattern)`：`pattern.startsWith("*.")` → `host.endsWith(pattern.substring(1))`（点号边界）；否则 `host.equals(pattern)`。
- **Rationale**: 课件是本节唯一权威实现来源；`endsWith(".example.com")` 天然挡住 `evil-example.com`（不以点号开头的后缀）与裸域 `example.com`（不含前导点），且匹配多级子域——满足 spec 全部回归点。
- **Alternatives considered**: 正则匹配域名——被否，课件明确用 `endsWith` + 点号边界，正则更易写错且是课件点名的"经典漏洞"反面教材。

## Decision 3：空白名单 = deny-all，无需特判

- **Decision**: 三类白名单为空列表时，`anyMatch` 对空流恒返回 `false`，天然 deny-all；不写任何"空则放行"的特判。
- **Rationale**: 符合 spec FR-005（配置为空 = 什么都不允许，非"不校验"）；语言语义即安全默认值，越少特判越不会漏。
- **Alternatives considered**: 空列表放行——被否，直接违反 FR-005 与宪法 VI（安全默认）。

## Decision 4：三校验方法 private，对外只暴露 enforce

- **Decision**: `checkFilePath/checkShellCommand/checkHttpUrl/matchesDomain` 均 `private`，`WhitelistSandbox` 对外只实现 `Sandbox.enforce(SandboxAction)`。
- **Rationale**: 课件 §3.2 明确点出——若三方法 public 暴露在接口上，接口就被这一档实现带偏；它们是实现内部的代码组织自由，不是契约。契约稳定性（宪法/接口先行）要求接口只有 enforce。
- **Alternatives considered**: 方法 public 便于单测——被否，测试通过 `enforce(new SandboxAction(type, target))` 走公共入口即可全覆盖，无需暴露内部。

## Decision 5：NotifyTools 改造点——构造加 Sandbox，send 前 enforce

- **Decision**: `NotifyTools` 构造签名从 `(Map<String,NotifyChannelAdapter>)` 改为 `(Map<String,NotifyChannelAdapter>, Sandbox)`；`execute` 在 `adapter.send(...)` 之前，从解析出的渠道配置取推送 URL（`resolved.config().get("url")`），`sandbox.enforce(new SandboxAction(ActionType.HTTP_REQUEST, url))`。与 `http_post` 共享同一份 `http.allowed_domains`。
- **Rationale**: 第19节课件已在该行留注释预告此改造点（grep 实证 NotifyTools:81）；推送是对外 IO，宪法 VI 要求过沙箱。enforce 抛异常时不 catch，直接上抛至 `ToolExecutor` 走失败审计（与 FileTools/HttpTools 一致）。
- **Alternatives considered**: 在 adapter 内部校验——被否，会让每个渠道实现各自接沙箱、易漏；集中在工具执行入口校验最可靠。

## Decision 6：装配替换

- **Decision**: `OryxOsRuntime.sandbox()` 从 `new PermissiveSandbox()` 改为 `new WhitelistSandbox(fileProps, shellProps, httpProps)`；`@EnableConfigurationProperties` 追加三配置 record；`toolRegistry` 处 `new NotifyTools(notifyAdapters, sandbox)`。`PermissiveSandbox` 类保留（留档/未来 Demo 用），装配处不再引用。
- **Rationale**: FR-009 要求运行时替换；三配置 record 用 Spring `@ConfigurationProperties` 从 `application.yml` 绑定。
- **Alternatives considered**: 删 PermissiveSandbox——被否，课件将其定位为"Demo 验证专用"，留档无害且避免删既有交付物触发软门禁。

## 依赖核实

- `java.nio.file.Path`、`java.net.URI`：JDK 标准库，`mvn dependency:tree` 无需新增。
- `@ConfigurationProperties` + `@EnableConfigurationProperties`：Spring Boot 既有，第16节 `ProvidersProperties` 已用同形态（record + 构造绑定），P3C 已验证可过。
- Mockito `verify(never())`：oryxos-tool 测试既有依赖（第20节 HttpToolsTest 已用）。
