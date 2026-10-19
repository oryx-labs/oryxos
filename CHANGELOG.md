# Changelog

本文件记录 OryxOS 的版本变更。格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

## [Unreleased]

## [0.1.8-RELEASE] - 2026-10-19

### Added
- 健康检查回显构建版本：`/api/v1/health` 返回与 Maven 产物同源的 `version` 和 git `build`（#961）。
- 成本看板：按 session / trace 下钻成本（#962）。
- ReAct：按 Agent 配置 `max_iterations` 收敛策略（`error` | `summarize`）（#749）。
- 渠道调用方校验：入站 webhook 可要求调用方令牌，集群模式强制校验；Google Chat / Teams 校验调用方 JWT；无令牌渠道启动时告警（#951/#953/#954/#955）。
- Telegram 长轮询超时可配（#960）。
- 运维提示：集群跑在本地工作区存储上时 CLI 告警（#1018）；路由策略没有信号源时告警（#1015）；MCP 仍使用已弃用 SSE 传输时告警（#1001）。

### Changed
- 渠道模块归到 `channels/` 聚合（#747）。
- Maven 最低版本提高到构建实际所需版本；依赖与 GitHub Actions 小版本升级（#939/#948/#949）。
- 缺省日程时区统一为 UTC（#848）。
- LLM 调用的连接、读取与整次请求超时分开配置（#893）。

### Fixed
- 确定性拒绝在 JSON 与 SSE 中都返回 4xx（#1033）；audit / evals 前缀的写方法默认拒绝（#976）；补登记曾挡住 RBAC 启动的端点（#968）。
- 工作区控制台文件 API 只暴露实际可浏览的根（#1025/#1032）；主密钥文件视为保留系统文件（#1020）；启动在监听端口之前拒绝非法配置（#1021）。
- CLI：输入错误单行报出、轻量命令失败非零退出、数据源占位符用 Spring 解析、生成主密钥前先过密钥守卫、删除 Agent 归档并抬工作区版本、provider 列表读注册表（#1006~#1014/#1031）。
- 知识库：删除限定所属库、路径入口校验库名、拒绝清单同名与软链别名、重启续跑中断文档（含集群）、向量宽度不一致时拒绝向量路由、切分前统一换行（#875~#995/#1022）。
- 渠道：长回复分段（含企微客服、Matrix、Teams）、只剥离本机器人提及、签名常量时间比较、Webhook/WebSocket 连接按条释放并限时关闭、钉钉回复 @ 对应提问者、WhatsApp 读取媒体说明（#905~#937/#993/#999/#1003/#1024）。
- Provider 与成本：402 切换备用、预算门禁覆盖流式调用、负成本钳为零、删除后丢弃缓存客户端、失败尝试先落审计再重试（#873/#970/#973/#974/#977/#996/#997/#1002/#1005）。
- 审计与账本在 SQL 内聚合，并为时间窗加索引（#899/#1010/#1016）；webhook URL 落库加密（#1012）；停用账号不再解析出角色（#871）。
- 其他：ReAct 中断时补齐 tool result（#758）；日程触发时刻落库以便核对恰好一次（#950）；两副本同时做启动播种不再互相踩（#972）；`make_dir` 不再把 `AGENT.md` 槽位变成目录（#927）；SSH 参数逐个引用（#923）。

### Docs
- 模块结构与 29 个 Maven 模块对齐；修正三处与代码不符的行为描述和四处渠道上限注释（#941/#942/#1026）。
- README 版本徽章升级至 0.1.8；Helm `appVersion`、健康检查契约示例与 A2A Agent Card 默认版本对齐 `0.1.8-RELEASE`。

## [0.1.7-RELEASE] - 2026-10-08

### Added
- A2A 互通：发布 Agent Card、JSON-RPC `message/send` 与 `message/stream`（SSE）、出站客户端 + `a2a_send` 工具、共享令牌鉴权、`X-A2A-Hop` 跳数上限（#704/#706/#708/#710/#712/#716）。
- 团队任务（Direction I）：协调者编排 API、专家并行扇出、after/dependsOn 波次调度、失败有界重规划、JPA 持久化与 `GET /api/v1/team-tasks`、耐久 HITL 挂起/恢复、审批策略门禁、成本归因、远程 A2A 节点路由；CLI `team-task run/get/list`（#687~#740）。
- Flow：AGENT 节点接入 `FlowAgentRunner`、TEAM_TASK 节点桥接团队编排、自然语言生成 Flow Markdown 草稿（#684/#690/#742/#744）。
- 能力目录：统一 `CapabilityRef` 目录与按轮能力叠加（#746/#753）。
- 工具与沙箱：进程内 `delegate_agent` 交接、docker 后端 `execute_code`、SSH 执行后端（#685/#686/#694）。
- MCP：Streamable HTTP 传输（`sse` 别名）与首连自动选择 HTTP 传输（#678/#725）。
- 评测：审计 trace 导出为 EvalCase 夹具（#696）。

### Changed
- Spring Boot starter parent 与 logstash-logback-encoder 等依赖升级；CI Actions 版本升级，PMD 暂锁 7.27.0（#726/#727/#813/#814 等）。
- 检出统一换行符规范化（#755）；gitleaks 下载失败自动重试（#757）。

### Fixed
- 10 MiB 读写上限补齐：PersonaStore、AgentStore、工作区写入、知识上传、CLI 导入/展示、文件工具等（#650~#677）。
- 渠道可靠性：租约续期/释放/回滚、去重认领释放、配额与媒体限制、长回复分段代理对、凭证掩码不回写、Telegram 媒体下载与令牌校验、钉钉 webhook 令牌不入错误信息（#777~#825）。
- MCP：重名/无名配置拒绝、关闭时断开连接、存活探测、先持久化再拆旧连接、空 env/headers 提示、凭证掩码（#768~#858）。
- 沙箱：未知执行后端拒绝而非回退本机、`execute_code` 按实际后端门禁、SSRF 判断隧道真实地址；路径边界判定穿透软链（#832/#847/#852/#854）。
- 记忆：工具缺参拒绝、SQLite 档关键词大小写折叠与 `%`/`_` 字面匹配、embedding 维度变化重建向量索引（#845/#856/#860）。
- 其他：Web 控制器与所注入 bean 同条件注册、RBAC 开启时 A2A 端点可启动、A2A 网关按映射后路径判定、`chat --profile` 拒绝不存在的 Agent、团队任务依赖等待与计划解析、集群心跳须短于租约（#751/#762/#766/#767/#770/#774/#786）。

### Docs
- 039 RBAC 用户角色面文档、Vision/roadmap 状态同步、Windows 需在 WSL2 内启动服务说明（#641/#688）。
- README 版本徽章升级至 0.1.7；Helm `appVersion` 与 A2A Agent Card 默认版本对齐 `0.1.7-RELEASE`。

## [0.1.6-RELEASE] - 2026-09-23

### Added
- 企业控制面深化：OIDC/SSO、组织与团队目录、durable membership、workspace ACL 祖先匹配、治理修订历史与 Admin UI（#454/#461/#462/#463/#504 等）。
- HITL 与耐久任务：高风险审批策略契约、耐久任务状态机与检查点恢复、管理台与 IM 审批交互（#464/#465/#466）。
- 可靠 Flow：Markdown Flow DSL、持久化执行引擎、人工等待/超时/取消、补偿与时间线回放（#467/#468/#469）。
- 可观测与评测：Agent/Skill/Prompt/Flow 离线评测 harness 与回归门禁（#472）；OTel/OTLP 指标与可观测模型（#470/#471）。
- 成本与模型治理：任务级成本账本/预算配额（#476）；可解释模型路由与成本策略（#477）。
- 集群资产源：Agent/Skill/Knowledge 共享内容版本源（#473）；工作区存储插件（本地/共享 POSIX）、原子发布与维护恢复（042，#599）。
- K8s 交付：官方 Helm Chart、探针与滚动升级约定（039，#486）；会话属主/文件面共享（026/027）。
- 三个企业标杆方案：知识支持 / 工单流程 / 受控研运（#478/#479/#480，Epic #460）。
- MCP：per-server `request_timeout` 可配（#428/#521）。
- CI 安全与质量：Dependabot、gitleaks、CodeQL、Trivy、CycloneDX SBOM、JaCoCo 行覆盖门禁、集成测试门禁、Actions SHA pin、PR 测试报告（#453 等）。

### Changed
- Spring Boot 4.0.8 + Spring AI 2.0.1（#600/#636）。
- Release 改为 tag-only 触发：合入 `release:` PR 后推送匹配 pom 的 `vX.Y.Z-RELEASE` 标签再发版（#612）。

### Fixed
- 存储暂时不可用时保留已加载 Agent/Skill；知识解析与内容指纹使用同一源文件快照。
- 工作区 / AGENT.md / MEMORY.md / 管理台文件读统一 10 MiB 上限（#638/#640/#643/#645）。
- 渠道回调 XML 空 CDATA 段解析误伤去重（#615/#616）。
- OWASP 2026-09 依赖波次升级与条件豁免复审（#498 等）。

### Docs
- README 版本徽章升级至 0.1.6；Helm `appVersion` 对齐 `0.1.6-RELEASE`。

## [0.1.5-RELEASE] - 2026-09-11

### Added
- 可插拔存储：Flyway 收编 schema 演进 + PostgreSQL 部署选项（025，#409）。
- 容器沙箱：docker 档最小闭环（024，#406/#362/#383）。
- Provider 失败按序切换备用 Provider，并导出业务指标（023，#377）。
- 密钥加密存储：落库凭证 AES-GCM，`ORYXOS_MASTER_KEY` / `master.key`（022，#351）。
- 审计 Trace：单轮处理串联 `trace_id`（021，#350）。
- 人格库：7 字段人格 + agency-agents 导入 + CRUD（#378）。
- Docker 镜像分发：Dockerfile / entrypoint / GHCR 多架构推送（#331）。
- 全球 IM 渠道波次：Telegram / WhatsApp / Teams / Google Chat / Mattermost / Matrix 入站与 notify；Slack / Discord notify 补齐（026，#429/#430）。
- Slack Socket Mode 入站 MVP 与图片/文件下载（#420/#421）。
- Discord Gateway 文本与图片/语音/视频入站（#422–#427）。
- 飞书 / 企微 / 钉钉：入站文件、音视频加深、进度流与交互体验（#414/#415/#411/#405 等）。
- 入站图片以多模态 Media 交给 LLM（#385）；IM `/new`、群 `/stop`、进度卡片与 ASR/PDF 抽取（#391/#412/#414 等）。
- QQ 官方 Bot 入站与 notify；入站图片/PDF/语音/视频（029，#434/#435）。
- 抖音私信 webhook 入站 MVP（031，#436）。
- 微信个人号 iLink 入站与多模态媒体（035，#437）。
- 微信客服入站 MVP（033，#440）。
- Agent 流式运行管理工作台（#219）；新建/编辑 Agent 时已安装 Skill 筛选（028）。
- `web_search` DuckDuckGo HTML 回退；默认工具脚手架含 `web_search`（#419 相关）。

### Fixed
- 凭证掩码：MCP / notify-channel 视图不再回显明文（#358）。
- Web/core 硬化余项：知识库命名空间、客户端错误映射、Skill 导入流式边界等（#360）。
- 飞书 WS close 有界，停机不再卡约 32s（#417）；`WorkspaceWatcher` SIGTERM 优雅退出（#333）。
- 企微启动失败关闭 WS（#349）；工具超时与边界（#356）；知识库索引生命周期（#357）。
- Actuator 门禁与默认关闭 Swagger（#314）。
- 入站媒体 SSRF / Whisper / TTL / 进度 UX 硬化与渠道对齐修复（#416/#413/#408 等）。
- 依赖：PostgreSQL JDBC 42.7.12 修复 CVE-2026-54291；Spring 相关 CVE 门禁处理。

### Docs
- 026 IM 渠道路线与真机验收记录；Demo 默认模型示例更新为 `deepseek-v4-flash`（#336）。
- README 版本徽章升级至 0.1.5。

## [0.1.4-RELEASE] - 2026-08-31

### Added
- 本地知识库：解析/切分/向量化索引与双路召回检索（`feat(knowledge)`，#202/#205）。
- 长期记忆语义召回：配置 `embedding.*` 后三路加权融合（015，#207）。
- 飞书 IM 入站渠道（017，#235）。
- 企业微信 AI 机器人长连接入站（#289/#290）；群回复引用原消息（#323）；断线自动重连（#319）。
- 钉钉 Stream 入站渠道 Runtime 装配与部署文档（#328）。
- REST API Key 认证：`/api/v1` 机器调用门禁（018，#249）。
- SSE 流式响应：三端点打字机 + CLI/管理台消费面（019，#263）。
- Tool Policy 工具策略：全局/Agent 级 allow/deny 治理层（020，#315）。
- SMTP 邮件通知渠道与 SMTP 端点白名单（#246）。
- 钉钉/飞书富文本与卡片通知格式（#248）；企业微信 Markdown 通知（#234）。

### Fixed
- API Key 过滤器覆盖 `/api/v2` 端点（#311）。
- 知识库索引跳过软链并强制 realpath 边界（#313）。
- 拦截对管理配置与 SQLite 库文件的读取（#312）。
- Profile 加载时 realpath 复检与 fail-loud（#309）。
- `make_dir` 绑定槽位防软链别名绕过（#64da84d）。
- IM 厂商 notify/inbound 默认 `http.allowed_domains` 对齐（#321）。
- 厂商 webhook 2xx 业务错误 fail-loud（#316/#317）。
- 文件工具写前 mutation guard 复检、Skill/Knowledge/AGENT.md 写保护强化（#273/#297–#308 等）。
- Profile YAML 严格校验：cron/ZoneId、列表字段类型、notify_channels/schedules 对象形态（#265–#267/#281–#296）。
- MCP `mcp_servers.yaml` 畸形配置 fail-loud（#271）。
- `WorkspaceWatcher` 对 `AGENT.md` 大小写不敏感（#243）。
- 无触发足迹时归档记忆修复（#208）。
- Windows 测试：路径分隔符与软链/POSIX 假设守卫（#262/#310）。
- 管理台登录页静态资源 401 白屏（018 配套）。
- 依赖升级：Tomcat 10.1.59、pdfbox、commons-lang3、springdoc/swagger-ui（#277/#287）。

### Security
- HTTP DNS rebinding：绑定已校验 DNS 解析结果（#278）。
- SSRF：IPv6 Teredo/6to4/ISATAP/IPv4-compatible 嵌入地址展开后私网拦截（#253/#269/#279/#280）。
- HTTP 重定向跨域剥离 `Authorization`/`Cookie`/`Api-Key`/`Private-Token`/`X-Access-Token`/`Deploy-Token` 等凭证（#241/#259/#291/#294）。
- HTTP 302 重定向剥离 `Content-Type` 等 body 头（#275）。
- 拒绝向 `channels.yaml`/`mcp_servers.yaml`/共享 Skill 实体直接写入（#237/#238/#239）。
- `MEMORY.md` 软链别名变异拦截（#251）。
- `http_request` 拒绝不支持的方法（#257）。

### Docs
- 路线图 v0.2/v0.3 交付状态与 HITL/Tool Policy 调整。
- SMTP 与通知白名单文档同步。
- README 版本徽章升级至 0.1.4。

## [0.1.3-RELEASE] - 2026-08-18

### Added
- Windows PowerShell 安装脚本（`feat(scripts)`，#176）。
- Provider 连通性测试（#97）。
- LLM Provider HTTP 调用 connect/read 超时（#88）。
- 管理台重复登录失败按用户名与来源 IP 锁定（`feat(web)`，#92）。

### Fixed
- Provider `/models` RestClient 设置 connect/read 超时，避免挂起阻塞管理台（#178）。
- 控制台日志显式 UTF-8 charset，修复中文 Windows 终端乱码（#176）。
- `download_file` 在 `createDirectories` 之后、`Files.write` 之前复检 `FILE_WRITE`（#180）。
- `edit_file` 在 `readString` 前复检 `FILE_WRITE`（#184）。
- `list_dir` 列目录前复检 `FILE_READ`（#175）。
- `write_file`/`append_file`/`edit_file` 写前复检 `FILE_WRITE`（#166）。
- `download_file` 写前复检 `FILE_WRITE`（#144）。
- `delete_file` 目录检测不跟随软链（#146）。
- `copy_file`/`move_file` 写前复检沙箱；拒绝目录目标（#148）。
- `grep`/`glob` 遍历前解析目录软链；`**/` 匹配 walk 根目录文件（#150）。
- `json_extract` 区分 JSON `null` 与路径缺失（#152）。
- Tool RestClient connect/read 超时（#138）。
- 本地 vLLM/Ollama 端点强制 HTTP/1.1（#133）。
- Provider ChatModel 缓存按 provider name 有界（#84）。
- shell stdout/stderr 与 CLI stdin 按默认 charset 解码（#129/#121）。
- `serve`/`gateway` 无交互环境抛出 `UnsupportedUserInteraction`（#119）。
- `notify` 默认 channel 从全局 registry 读取（#123）。
- HTTP write 与 `notify` 重定向每跳复检白名单（#112/#117）。
- 审计写入失败不再掩盖 LLM/Tool 主流程结果（#95）。
- `MEMORY.md` 追加串行化与原子写入（#86）。
- Markdown 记忆条目 section header 消毒（#164）。
- 管理台 `BasicAuthFilter` 应用登录锁定（#161）。
- 会话聊天新回复自动滚底；重载后保留滚动位置（#100/#101）。
- Skill 导入 GitHub 路径空格编码为 `%20`（#142）。
- HTTPS 反代下 `Secure` cookie 尊重 `X-Forwarded-Proto`；登录使旧 session 失效（#90）。
- 恢复 `main` CI 绿：spotless + SpotBugs（#115）。
- CI spotless 快速失败与 deploy-pages 超时（#162）。
- 默认模型示例改为 `deepseek-v4-flash`（#80）。

### Security
- 修复 shell 白名单 bypass（#98）。
- HTTP 跨域重定向时剥离 `Authorization`/`Cookie` 等敏感凭证头（#182）。
- `web_search` 重定向 SSRF：`Redirect.NEVER` + endpoint `HTTP_READ` 校验（#156）。
- NAT64/IPv4-mapped 嵌入 SSRF 拦截（#127）。
- HTTP 请求必须 http(s) scheme；HTTP_READ 必须 http(s) host（#136/#131）。
- Skill 导入 SSRF 拦截 IPv6 ULA（#110）。

### Docs
- shell/HTTP 沙箱文档与实现对齐（#125）。
- HTTP 白名单文档区分 HTTP_READ vs HTTP_REQUEST（#104）。
- 英文文档 allowlist → whitelist（#105）。
- 修复过时 jar 路径与文档链接（#106）。
- 中文快速开始补充 Windows 说明（#102）。
- 网站侧边栏增加 Auth 页（#99）。
- README 版本徽章升级至 0.1.3。

## [0.1.2-RELEASE] - 2026-08-10

### Added
- 管理台概览页接入实时数据源，新增会话统计端点（`feat(web/api)`）。
- Agent 会话聊天支持可配置的键盘发送方式（`feat(web)`）。
- Web 端支持选择模型、编辑 Agent 详情（`feat(web)`）。
- 渐进式 Agent Skill 加载（`feat`）。

### Fixed
- `/api/v1/agents/{name}/invoke` 改为无状态调用：每次用独立内存会话，不再泄漏隐藏会话（#68，Closes #49）。
- 会话并发写入丢消息修复：进锁重读 + CAS 条件更新（冲突返回 409）、SQLite WAL + `busy_timeout`、审计 fail-closed、持久历史按整轮截断（#69，Closes #43）。
- 更新安全基线与依赖扫描，处理传递依赖 CVE 门禁（#71）。
- `WorkspaceWatcher` 递归监听 Agent 子目录，修复 `AGENT.md` 变更漏检（#63，Closes #61）。
- 恢复 `main` 分支 CI 绿：spotless 格式、P3C 误报/魔法值、掩码测试对齐（#64）。
- 修复七项高风险安全与并发问题（#58）。
- Provider 数据库配置跨重启保留（#56）。
- 支持 `baseUrl` 自带版本段的 OpenAI 兼容端点（如智谱 GLM `/api/paas/v4`）。
- 统一 `baseUrl` 约定（不含 `/v1`），修复 OpenAI 兼容 Provider 404。
- Provider 校验前移到端口绑定之前，避免误报启动失败。
- 恢复管理台认证；用户账户管理命令不再强制要求 LLM api-key。
- `bin/start.sh` 在 jar 未构建时正常报错，不再静默吞掉。
- 更新默认模型示例为 `deepseek-v4-flash`。

### Changed
- 复用 `requireAgent` 收敛重复的存在性检查（#65）。
- Skill 页面简化为纯 CRUD（`refactor(web)`）。

### Security
- 明确 shell 沙箱边界：默认命令白名单移除 `python3`，锁定为 `ls`/`cat`/`echo`/`grep` 最小权限，并补文档警示解释器会放大 Agent 权限（#70，Closes #41）。

### Docs
- 新增根 `CONTRIBUTING.md` 并链接贡献指南（#60）；澄清 Agent 目录相关措辞（#59）。
- 对齐 notify channel 文档与当前实现；修正 `serve` 命令过时的帮助文本（#62）。
- 修复 OryxOS 概览 logo 链接失效（#55）。
- README 版本徽章升级至 0.1.2（#72）。

## [0.1.1-RELEASE] - 2026-07-23

### Added
- 管理台认证（012-web-auth）。
- 管理台概览统计从 API 加载。

### Fixed
- HTTPS 请求下的认证 Cookie 安全属性。

### Docs
- 网站 canonical/sitemap 指向 github.io 域名。

## [0.1.0-RELEASE] - 2026-07-22

首个发行版：面向企业场景的 Distributed AI Agent OS 运行时内核（9 个 Maven 模块）。

### Added
- 自实现 ReAct Loop、`PromptBuilder`、`ToolExecutor`。
- Provider 显式映射（`ProviderService`），基于 Spring AI Alibaba 做协议转换。
- 长期记忆体系（`MemoryService`、`MEMORY.md`、`save_memory`/`recall_memory`）。
- 内置 Tool（文件/Shell/HTTP/notify）+ MCP Client + `SandboxChecker` 白名单沙箱。
- 「一个目录 = 一个 Agent」的 `AGENT.md` 加载（frontmatter → Profile + 正文注入 system prompt）。
- Web REST API（`/api/v1`）与 CLI 子命令（`init`/`chat`/`serve`/`gateway` 等）。
- SQLite 持久化与 `tool_invocations` / `llm_calls` 审计表 Day-One 写入。

[0.1.5-RELEASE]: https://github.com/oryx-labs/oryxos/compare/v0.1.4-RELEASE...v0.1.5-RELEASE
[0.1.4-RELEASE]: https://github.com/oryx-labs/oryxos/compare/v0.1.3-RELEASE...v0.1.4-RELEASE
[0.1.3-RELEASE]: https://github.com/oryx-labs/oryxos/compare/v0.1.2-RELEASE...v0.1.3-RELEASE
[0.1.2-RELEASE]: https://github.com/oryx-labs/oryxos/compare/v0.1.1-RELEASE...v0.1.2-RELEASE
[0.1.1-RELEASE]: https://github.com/oryx-labs/oryxos/compare/v0.1.0-RELEASE...v0.1.1-RELEASE
[0.1.0-RELEASE]: https://github.com/oryx-labs/oryxos/releases/tag/v0.1.0-RELEASE
