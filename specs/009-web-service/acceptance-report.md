# 第26节验收报告：Web Service 与第一版管理平台

**日期**: 2026-07-15 | **分支**: `class-26` | **任务**: 26/26 完成（tasks.md 全勾）

## 六项证据 DoD

### 1. `mvn clean verify` 全绿 ✅（Java 全量 + 静态门禁）

`mvn clean verify -Dfrontend.skip=true` → `BUILD SUCCESS`，含 Spotless/Checkstyle/P3C/SpotBugs/FindSecBugs。测试 ~219 个全过：

```text
oryxos-core 40 | oryxos-provider 14 | oryxos-storage 20 | oryxos-memory 26
oryxos-tool 96 | oryxos-web 23（Session3 + Agent3 + GlobalExc5 + Sandbox 12）
```

> **前端构建说明（诚实交代）**：管理台 Vue 已用 `npm run build` 独立验证成功（`✓ 12 modules → static/admin/index.html + assets`，见下 §怎么验）。frontend-maven-plugin 已绑进 `mvn`（generate-resources），但它每次 `mvn clean` 会重下自带 node，整机 verify 很慢；故 Java DoD 用 `-Dfrontend.skip=true` 跑绿，前端构建单独验证。`static/admin` 产物在 `src/main/resources` 下、随包分发（已 gitignore，打包由插件重建）。

过程中被门禁拦下并修复：Spotless 格式；SpotBugs `EI_EXPOSE`（ProfileView/SessionView/InfoView 三个 record 加 `List.copyOf` compact 构造）；`SPRING_ENDPOINT`（六个 Controller 局部 `@SuppressFBWarnings`，核心阶段无认证是设计前提，与既有 SandboxWhitelistController 同款）。

### 2. harness 测试类逐一对号 ✅

- `SessionApiControllerTest`（3，@standalone MockMvc）：`messageOver32kb_returns400`、`sessionNotFound_returns404`、**`normalMessage_callsProcessExactlyOnce`**（`verify(agentService).process` 恰一次，Controller 不夹带私货）。
- `GlobalExceptionHandlerTest`（5）：400/404/503/504 各映射 + **关键回归 `internalDetailsNeverLeakIn500`** `@DisplayName("内部异常细节_绝不能出现在500响应里")`（注入含 `jdbc:sqlite` 异常，断言响应体不含连接串、message=统一话术）。
- `AgentApiControllerTest`（3）：invoke 走 process、未知 Agent→404、超 32KB→400。
- `WebSmokeIT`（@Tag integration，standalone）：health/info/profiles/tools/memory 只读端点可达 + 统一信封。
- 既有 `SandboxWhitelistControllerTest`/`WebMvcTest`（12）保持绿——共用 ApiResponse 信封。

### 3. 交付物存在性核对 ✅

- 六个 Controller（Session/Agent/Profile/Memory/Tool/System，10 端点）+ DTO records + `error/` 异常 + `config/WebConfig`（SPA 回落）；`GlobalExceptionHandler` 扩展。
- 统一信封复用既有 `ApiResponse`（**ErrorBody 反向同步**：TechSol §7 + 第26节课件 §三 已改）。
- 两处前序接口扩展（停点确认）：`SessionManager.archive` + JpaSessionManager 实现（status=archived/archived_at）；`MemoryService.readAll` + MemoryServiceImpl 实现（委托 store.load）——各补测试。
- `application.yml` 排除 `OpenAiAutoConfiguration`（serve 只需 DEEPSEEK_API_KEY）。
- 管理台 Vue 3 + Vite（`oryxos-web/src/main/frontend/`，`oryxos-admin-ui` skill 生成风格）+ frontend-maven-plugin 绑进 mvn + `.gitignore` 产物。

### 4. 前序节回归 ✅

16~25 节全部测试全绿（core 40 含 archive/scheduler、storage 20 含 archive、memory 26 含 readAll、provider 14、tool 96）。含 class-26 检出时的 D1 provider 回退修复。SessionManager/MemoryService 接口扩展未破坏任何既有实现（唯一实现类各自更新，无手写 fake）。

### 5. H4 六条全局不变量自查 ✅

| # | 不变量 | 结论 |
|---|---|---|
| ① | 涉外 IO 过 Sandbox | Web 层不做涉外 IO；发消息/invoke 经 AgentService→ReAct→工具→Sandbox 既有链路 |
| ② | 审计成败都落库 | 走 AgentService.process 内部既有 llm_calls/tool_invocations；web grep 无新增审计逻辑 |
| ③ | 无明文 key | grep 零命中 |
| ④ | session_id 只在 SessionManager 拼 | web 只传三元组给 getOrCreate / 用 path id 调 get/archive，grep 无拼接 |
| ⑤ | 无 Reactor/CompletableFuture/自建线程池 | web main grep 零命中；Spring MVC + 虚拟线程，Controller 同步 |
| ⑥ | 无 Spring AI 自动执行 | 本节不触碰 LLM 调用路径 |

### 6. 剩余人工项（harness 判不了，等你过）

1. **真模型发消息**：`serve` 后 `POST /sessions/{id}/messages` 真跑一轮、审计有账。
2. **CLI 与 REST 同源**：`oryxos chat` 聊过的 session，`GET /sessions/{id}` 查得到同一份。
3. **503/504 故障注入**：断 Provider 拿 503、构造超 60s 拿 504，服务不崩。
4. **并发压**：200 并发 invoke，虚拟线程扛得住。
5. **管理台 + 整机冒烟**：`mvn package`（不 skip，插件构建 admin）→ `serve` → `/admin` 五页渲染真实数据、无写入口、视觉同首页；`/swagger-ui` 齐；真实上下文 JPA 仓库扫描 >0（WebSmokeIT 的 standalone 版覆盖 web 层，整机 JPA-scan 靠此手动冒烟）。
6. **启动只需一个 key**：只配 `DEEPSEEK_API_KEY`，serve 正常起。

## 备注

- **信封统一 ApiResponse**（停点确认）：课件/TechSol 原写 ErrorBody 反向同步为既有 ApiResponse，全站一套信封，白名单端点零返工。
- **两处接口扩展**（停点确认）：SessionManager.archive、MemoryService.readAll——完成 18/22 节 data model 已设计但未接线的能力，非新概念；archive 返 boolean（core 不依赖 web 异常，404 由 Controller 抛）。
- **/info providers 口径**：取"已加载 Profile 引用到的 Provider 名单"（web 只依赖 core，看不到 providerMap/spring-ai ChatModel）；连通性以"已配置"为准、不做 live 探活（扩展阶段）。
- **SessionView 去掉 status**：core Session 无 status 字段（仅持久化实体有），为不再改接口，GET 视图只回 sessionId/profile/messages；归档状态由 DELETE 返回。
- **WebSmokeIT 用 standalone**：oryxos-web 无 @SpringBootApplication（app 在 cli/boot），整机 @SpringBootTest 冒烟不在只依赖 core 的 web 模块内；standalone 覆盖 web 层路由/信封，整机 JPA-scan 冒烟归手动 serve（人工项）。
- **前端产物 gitignore**：node_modules + static/admin 不入库，由 frontend-maven-plugin 打包重建。
- 未 commit/push——同步时机由你决定。
