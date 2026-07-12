# Tasks: Sandbox 白名单——把工具执行前那道安全校验的墙真正砌起来

**Input**: specs/007-sandbox-whitelist/（plan.md / research.md / data-model.md / contracts/sandbox.md / quickstart.md）

**Tests**: 课件"验收 harness"——`WhitelistSandboxTest`（两个关键回归：路径穿越 normalize、通配符点号边界）+ 四工具各一条"白名单外被拦、底层 verify never"。测试方法名英文 + `@DisplayName` 保留课件原文。

**Organization**: 配置载体 + 实现（契约先行）→ 四工具接线/回归 → 装配 → 反向文档同步 + Polish。

**既定决策（停点已确认）**：`ActionType` 保留四值不改；`Sandbox`/`SandboxAction`/`SandboxViolationException` 不改签名；三工具（File/Shell/Http）execute 首行 enforce 调用第20节已接、**不改**；仅 `NotifyTools` 构造加 `Sandbox`；反向同步 `docs/TechnicalSolution.md §6.7` 与第24节课件 §3.1 的 `ActionType` 到四值。

## Phase 1: Setup

- [ ] T001 基线 & 依赖核实：`mvn test -q` 确认 16~22 节全绿；`mvn dependency:tree -pl oryxos-tool | grep -E "spring-boot|mockito"` 确认无需新增依赖（白名单纯 JDK `java.nio.file.Path`/`java.net.URI`；`@ConfigurationProperties`/Mockito 既有）

## Phase 2: Foundational（配置载体——三档白名单来源，阻塞 US1/US2/US3）

- [ ] T002 [P] `oryxos-tool/src/main/java/io/oryxos/tool/sandbox/FileSandboxProperties.java`——`@ConfigurationProperties(prefix="file")` record `FileSandboxProperties(List<String> allowedPaths)`（绑定键 `file.allowed_paths`）
- [ ] T003 [P] `oryxos-tool/src/main/java/io/oryxos/tool/sandbox/ShellSandboxProperties.java`——`@ConfigurationProperties(prefix="shell")` record `ShellSandboxProperties(List<String> allowedCommands)`（绑定键 `shell.allowed_commands`）
- [ ] T004 [P] `oryxos-tool/src/main/java/io/oryxos/tool/sandbox/HttpSandboxProperties.java`——`@ConfigurationProperties(prefix="http")` record `HttpSandboxProperties(List<String> allowedDomains)`（绑定键 `http.allowed_domains`）

**Checkpoint**: 三配置 record 就位，`WhitelistSandbox` 可注入。

## Phase 3: US1 越界被拦 + US2 白名单内放行 + US3 绕过被挡（P1，harness 先行——WhitelistSandbox 核心）

**Goal**: `WhitelistSandbox` 三类校验落地，越界拒绝/正常放行/绕过（路径穿越、形似域名）被挡。三个 P1 故事共享同一实现类与同一测试类，合并为一个 harness 阶段。

**Independent Test**: `mvn test -pl oryxos-tool -am -Dtest=WhitelistSandboxTest` 全绿——含放行、越界拒绝、两关键回归。

- [ ] T005 [US1] **harness 先行** `oryxos-tool/src/test/java/io/oryxos/tool/sandbox/WhitelistSandboxTest.java`——通过 `enforce(new SandboxAction(type, target))` 公共入口断言（三 `check*` 为 private 不直测）：
  - 文件：白名单内 `FILE_READ`/`FILE_WRITE` 放行、白名单外拒绝；**关键回归** `relativePathTraversalIsBlocked` `@DisplayName("相对路径穿越_爬出白名单目录_被拦")`（白名单 `/allowed`，`enforce(FILE_READ, "/allowed/../../etc/passwd")` 抛 `SandboxViolationException`）
  - 命令：白名单 `{ls,cat}`，`ls -la` 放行、`rm -rf /` 拒绝（首 token 比对）
  - HTTP：**关键回归** `wildcardDomainRespectsDotBoundary` `@DisplayName("通配符域名_命中真子域_不被形似域名绕过")`（白名单 `*.example.com`，`api.example.com`/`a.b.example.com` 放行、`evil-example.com` 拒绝、裸域 `example.com` 拒绝）；无主机畸形 URL 拒绝
  - 空白名单：任一类空列表 → 该类全部拒绝（deny-all）
- [ ] T006 [US1] `oryxos-tool/src/main/java/io/oryxos/tool/sandbox/WhitelistSandbox.java`——`implements Sandbox`；构造注入三 Properties（`allowedRoots=allowedPaths.map(Path::of).map(Path::normalize)`、`allowedCommands=Set.copyOf`、`allowedDomainPatterns=List.copyOf`；null 列表归一空列表）；`enforce` 传统 switch on `type()`：`FILE_READ`/`FILE_WRITE`→`checkFilePath`、`SHELL_COMMAND`→`checkShellCommand`、`HTTP_REQUEST`→`checkHttpUrl`；三 `check*`+`matchesDomain` **private**，实现逐字对齐课件 §3.2（见 contracts/sandbox.md）；避开增强 switch `default ->` 语法（P3C 门禁）
- [ ] T007 [US1] 阶段门禁：`mvn test -pl oryxos-tool -am -Dtest=WhitelistSandboxTest` 全绿；红了修 `WhitelistSandbox`（不删断言）

**Checkpoint**: 白名单校验器就位，三 P1 故事的核心逻辑全绿。

## Phase 4: US1 四工具拦截回归（P1）——白名单外被拦且底层 IO 零发生

**Goal**: 证明四个受管工具在越界输入下真的不发生 IO。三工具 enforce 第20节已接线（不改代码），本阶段补/核回归测试；NotifyTools 需接线（US4）。

- [ ] T008 [P] [US1] `oryxos-tool/src/test/java/io/oryxos/tool/builtin/FileToolsTest.java`——补/核一条 `readOutsideWhitelist_fileNeverTouched` `@DisplayName("白名单外文件_读取被拦_文件根本不碰")`：注入白名单只含临时目录的 `WhitelistSandbox`，`read_file` 白名单外路径 → 抛 `SandboxViolationException`、底层 `Files` 读取不发生（越界路径不存在/未被读取断言）
- [ ] T009 [P] [US1] `oryxos-tool/src/test/java/io/oryxos/tool/builtin/ShellToolsTest.java`——补/核一条 `commandOutsideWhitelist_processNeverStarts` `@DisplayName("白名单外命令_起进程前被拦")`：白名单 `{ls}`，`rm` → 抛异常、进程不启动
- [ ] T010 [P] [US1] `oryxos-tool/src/test/java/io/oryxos/tool/builtin/HttpToolsTest.java`——补/核一条 `requestOutsideWhitelist_restClientNeverCalled` `@DisplayName("白名单外域名_RestClient从未被调用")`：mock `RestClient`，白名单 `*.example.com`，`http_get` `http://evil.com` → 抛异常、`verify(restClient, never())`

**Checkpoint**: 三工具越界零 IO 证明就位（NotifyTools 在 US4）。

## Phase 5: US4 通知工具接上校验（P2）——第19节预告改造点

**Goal**: `NotifyTools` 推送前过 HTTP 域名校验；构造加 `Sandbox`。

**Independent Test**: `mvn test -pl oryxos-tool -am -Dtest=NotifyToolsTest` 全绿——越界域名推送被拦、adapter.send 从未调用。

- [ ] T011 [US4] **harness 先行** `oryxos-tool/src/test/java/io/oryxos/tool/builtin/NotifyToolsTest.java`——补一条 `pushToDomainOutsideWhitelist_adapterNeverSends` `@DisplayName("白名单外域名推送_adapter从不发送")`：mock `NotifyChannelAdapter`，Profile 配一个 url 在白名单外的渠道，白名单 `*.example.com`，`execute` → 抛 `SandboxViolationException`（上抛，不吞）、`verify(adapter, never()).send(...)`；另一条白名单内 url 放行、`adapter.send` 被调用（回归既有行为）
- [ ] T012 [US4] `oryxos-tool/src/main/java/io/oryxos/tool/builtin/NotifyTools.java`——构造签名加 `Sandbox` 参数（`NotifyTools(Map<String,NotifyChannelAdapter>, Sandbox)`）；`execute` 在 `adapter.send(...)` 之前从 `resolved.config().get("url")` 取 url、`sandbox.enforce(new SandboxAction(ActionType.HTTP_REQUEST, url))`（enforce 抛异常不 catch，上抛走 ToolExecutor 失败审计）；替换第81行注释接线位
- [ ] T013 [US4] 阶段门禁：`mvn test -pl oryxos-tool -am` 全绿（含 T005/T008~T011 全部）

**Checkpoint**: 四工具对外 IO 全过沙箱，US4 完成。

## Phase 6: 装配 + 配置

- [ ] T014 装配：`oryxos-cli/src/main/java/io/oryxos/cli/OryxOsRuntime.java`——`sandbox()` bean 由 `new PermissiveSandbox()` 改为 `new WhitelistSandbox(fileProps, shellProps, httpProps)`（三 Properties 作 bean 方法入参）；类级 `@EnableConfigurationProperties` 追加 `FileSandboxProperties.class`/`ShellSandboxProperties.class`/`HttpSandboxProperties.class`；`toolRegistry(...)` 处 `new NotifyTools(notifyAdapters, sandbox)`（sandbox 已是该方法入参）；移除对 `PermissiveSandbox` 的 import（类留档不删）
- [ ] T015 配置：`oryxos-boot/src/main/resources/application.yml`——追加 `file.allowed_paths`（默认含 `.oryxos`）、`shell.allowed_commands`（如 `ls`/`cat`）、`http.allowed_domains`（如 `*.example.com`/`api.deepseek.com`）三块，注明"空=deny-all、按最小权限收窄"

## Phase 7: 反向文档同步 + Polish

- [ ] T016 [P] 反向同步 `docs/TechnicalSolution.md §6.7`——`ActionType` 从三值（FILE_ACCESS/SHELL_EXEC/HTTP_REQUEST）改为四值（FILE_READ/FILE_WRITE/SHELL_COMMAND/HTTP_REQUEST），说明 `WhitelistSandbox` switch 把 FILE_READ/FILE_WRITE 同路由 checkFilePath
- [ ] T017 [P] 反向同步 `docs/class/第24节：Sandbox 实现与代码讲解.md §3.1/§3.2`——`ActionType` 示例改四值、`enforce` switch 改四 case（FILE_READ+FILE_WRITE→checkFilePath），与已交付代码一致；保留课件其余讲解
- [ ] T018 全仓硬门禁：`mvn clean verify` 全绿（含 Spotless/P3C/PMD/Checkstyle/SpotBugs/FindSecBugs），红了修实现（不放宽断言）
- [ ] T019 H4 六条全局不变量自查 + 交付物 ls/grep 核对（涉外 IO 过 Sandbox：四工具 enforce 全接线；无明文 key；三 check* 为 private grep 确认；Sandbox 接口仍只 enforce 一个方法）
- [ ] T020 验收报告：`specs/007-sandbox-whitelist/acceptance-report.md`（六项证据 + 人工项：真实链路越界留痕、接口中立性自查、配置边界写进文档）

## Dependencies

- T001 → 全部。
- T002/T003/T004（[P] 不同文件）→ T006（WhitelistSandbox 构造注入三 Properties）。
- T005（harness）先于/伴随 T006；T006 → T007。
- T006 → T008/T009/T010（工具测试需真 WhitelistSandbox）、T011/T012。
- T011（harness）先于/伴随 T012；T012 → T013。
- T006 + T012 → T014（装配需实现类与新构造）；T014 ∥ T015。
- 主代码全绿 → T016/T017（[P] 文档）；全部 → T018 → T019 → T020。

## Parallel Opportunities

- T002 / T003 / T004（三配置 record，不同文件）。
- T008 / T009 / T010（三工具测试，不同文件，均依赖 T006）。
- T016 / T017（两份文档，不同文件）。

## Implementation Strategy

- **MVP** = Phase 2+3（三配置 + `WhitelistSandbox` + `WhitelistSandboxTest` 两关键回归）——"真正的墙"证明就位。
- Phase 4/5 补四工具零 IO 证明与 NotifyTools 接线；Phase 6 一次装配；Phase 7 反向文档 + 门禁。
- 每阶段门禁当场修红，不攒到最后。
