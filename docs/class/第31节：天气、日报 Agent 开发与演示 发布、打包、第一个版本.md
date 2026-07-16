# 天气、日报 Agent 开发与演示：发布、打包、第一个版本

机制全部就位：底座跑得稳（27、28 节），Agent 能声明式定义（29 节）、能通过 API 动态管理（30 节）。这节是整个第一阶段的验收——做出**两个真实的业务 Agent**（每日天气、每日科技日报），演示它们自己按点干活，然后打包发布第一个版本。这两个 Demo 跑通，是核心功能发布的硬条件；它们就是"驾驭层"学成的证据。

---

## 一、这节要交付什么

一句话：**两个到点自动跑的 Agent + 一个打了 tag 的可部署版本。**

两个 Demo 是刻意挑过的，合起来把前面所有能力都压过一遍：

| | Demo 一：每日天气 | Demo 二：每日科技日报 |
|---|---|---|
| 场景 | 每天 08:00 查天气、生成穿搭建议、推到群里 | 每天 09:00 汇总科技新闻、推到群里，内容体现用户偏好 |
| 压到的能力 | Provider + ReAct + 内置 HTTP Tool + Sandbox + 定时 + Notify | SKILL.md 零代码 + MCP + Memory + 定时 + Notify |
| 创建方式（故意分两条路） | 走 30 节的 API / 管理平台表单 | 走 29 节的手写文件 |

创建方式分两条路也是刻意的：正好把"API 定义"和"文件定义"两条路径都当着自己的面走通一遍，验证 30 节那句"两条路径产物一致"。

## 二、Demo 一：每日天气 Agent

**第一步：定义。** 在管理平台的"新建 Agent"表单里填（或直接 `POST /api/v1/agents`）：

```json
{
  "name": "weather-daily",
  "description": "每天早上查北京天气并推送穿搭建议",
  "trigger": "每天早上 8 点定时触发",
  "required_tools": ["http_get", "notify"],
  "skill_content": "你是天气助手。被触发时：\n1. 调用 http_get 请求 https://api.open-meteo.com/v1/forecast?latitude=39.9&longitude=116.4&current=temperature_2m,precipitation,wind_speed_10m 获取北京当前天气；\n2. 根据天气给出一段简短实用的穿搭建议；\n3. 把'今日天气 + 穿搭建议'组织成一条消息，调用 notify 发送出去。",
  "provider": {"name": "deepseek", "model": "deepseek-chat"},
  "tools": ["http_get", "notify"],
  "schedules": [{"id": "weather-morning", "cron": "0 0 8 * * *",
                 "zone": "Asia/Shanghai", "message": "到点了，按你的技能说明执行。"}]
}
```

天气源钉死用 **open-meteo**（免费、免 API key、返回 JSON），白名单里加 `api.open-meteo.com`；`notify_channels` 用 28 节配好的团队 webhook——前置条件清单这时兑现价值。不自己挑 API 的原因很实际：Demo 现场最怕"接口要注册账号/要充值/被墙"这类和课程无关的意外。

> **跑起来之前，先过三道环境门（不过就是空转，别急着等钟推）：**
> 1. **启动 key**：`export DEEPSEEK_API_KEY=...`。因为 26 节已排除 `OpenAiAutoConfiguration`，`serve` 只需要这一个 key 就能起——若你发现它还索要 `spring.ai.openai.api-key`，说明 26 节那个排除没落地，回去补。
> 2. **白名单**（24 节 Sandbox 默认 deny-all，会拦自己）：`http.allowed_domains` 里必须有 `api.open-meteo.com` **和** 团队 webhook 的域名（飞书 `*.feishu.cn` / 企业微信 `qyapi.weixin.qq.com`，按实际渠道）。少一个，`tool_invocations` 就是一片 `success=false`。
> 3. **webhook 可达**：一个真能收消息的群机器人地址，配进 `notify_channels`。

**第二步：调试，先人推再钟推。** 别干等八点。先手动补跑一次把链路调通：

```bash
oryxos chat --profile weather-daily
> 到点了，按你的技能说明执行。
```

或者 `POST /agents/weather-daily/invoke`。人推和钟推走同一条链路（25 节验证过的），人推通了，钟推基本就通——这就是当初坚持"同一个 `AgentService` 入口"在调试体验上的回报。常见的两类问题这一步就能暴露：天气 API 域名没进白名单（Sandbox 拦截，看 `tool_invocations` 的 `error_message`）、Skill 说明写得含糊导致模型不调工具（改 Skill 正文，即时生效，再试）。

**第三步：等真正的钟推，对账。** 把 cron 临时改成几分钟后，看它完整自跑一轮：

![天气 Agent 端到端：定时触发、注入 Skill、查天气、生成建议、推送](../../website/public/images/class-31-1.svg)

对账清单（跟需求文档的验收标准一字对齐）：无人触发；`http_get` 和 `notify` 两次涉外调用都过了白名单、都写进 `tool_invocations`；`llm_calls` 两条；`GET /api/v1/sessions/{id}` 能查到这次自动触发的完整对话；群里收到了消息。

## 三、Demo 二：每日科技日报 Agent

**第一步：搞定"拉科技新闻"这个外部能力——两条路，先保证能跑，再谈实练。** 这个 Agent 需要一个拉当日科技新闻的能力，实现上有两条路，按"Demo 必须跑起来"的优先级选：

- **路一（稳，建议默认）：直接用内置 `http_get`。** 找一个免 key、返回 JSON、稳定可达的新闻源（如某科技媒体的 RSS-to-JSON、或公开新闻聚合 JSON 接口），Skill 里让 LLM 调 `http_get` 取回、自己挑选组稿。Profile 的 `tools` 加 `http_get`、白名单加该新闻源域名即可——不引入外部进程，Demo 命运完全握在自己手里。
- **路二（MCP 实练，锦上添花）：声明一个新闻 MCP server。** 在 `.oryxos/mcp_servers.yaml` 里声明，重启后 `oryxos tool list` 能看到它暴露的工具。这是 20 节"方式二"的真实练手。**若走这条，务必提前把 `news-mcp` 写好、连通验证过，不要 Demo 现场才写**——按 20 节方式二自己写一个最小 `news-mcp`（读两三个科技媒体 RSS、暴露一个 `fetch_tech_news` 返回标题列表，几十行任何语言都行），别赌社区某个 server 恰好活着；连不上时 MCP 会被 WARN 跳过（28 节"外部依赖不拖垮启动"），届时 Demo 就哑了。

两条路对 Skill 正文透明（都是"调用新闻工具拉新闻"），差别只在 Profile 怎么给这个能力：路一给 `http_get` + 白名单域名，路二给 `mcp_servers` + 对应工具名。**要点：Skill frontmatter 的 `required_tools` 与 Profile 的 `tools`/`mcp_servers` 必须跟所选路径一致**（29 节的一致性校验会查）。下面的示例按路二写，走路一就把"新闻工具"换成 `http_get` 并在 `tools` 里加上它。

**第二步：种一条偏好进记忆。** 先跟任意 Agent 聊一句：

```text
> 以后帮我关注科技新闻的话，我更关注 AI 和芯片方向。
```

模型判断值得长期记，调 `save_memory` 写进 `MEMORY.md`（22 节的机制）。确认文件里真的多了这一条再往下走。

**第三步：定义，这次走手写文件。** `.oryxos/skills/daily-tech-digest.md`：

```markdown
---
name: daily-tech-digest
description: 每天汇总当日科技新闻并推送
trigger: 每天早上 9 点定时触发
required_tools:
  - notify
---

你是科技日报编辑。被触发时：
1. 调用新闻工具拉取当日科技新闻；
2. 挑出最重要的 5~8 条，组织成一份简明日报；
3. 如果长期记忆里有用户关注方向的偏好，优先挑选并排列相关条目；
4. 调用 notify 把日报发送出去。
```

配上 Profile（`skills` 引用它、`mcp_servers` 引用新闻源、`schedules` 设 09:00），重启或用 30 节的 API 注册。**全程零 Java 代码**——一份 markdown、两段 YAML。

**第四步：调试与对账。** 还是先人推补跑，再看钟推：

![日报 Agent 端到端：定时触发、注入 Skill 和记忆、MCP 拉新闻、组稿、推送](../../website/public/images/class-31-2.svg)

对账重点比天气 Agent 多两条：**日报内容真的把 AI/芯片条目排在前面**——这是记忆在跨天场景里生效的直接证据，也是最能打动人的一刻，因为这个偏好不在 Skill 里、不在代码里，只在记忆里；**LLM 是自己决定调新闻工具、自己组稿的**——OryxOS 没有解析任何任务步骤，Skill 正文就是全部的"编排"。

## 四、发布、打包、第一个版本

两个 Agent 都稳定自跑之后，把整个东西变成一个可交付的版本：

```bash
mvn clean verify              # 全量测试最后一道关
mvn clean package             # 产出 fat JAR
git tag v0.1.0 && git push --tags
```

![发布产物：一个 fat JAR + 一个 .oryxos 工作区](../../website/public/images/class-31-3.svg)

发布不只是打个包，要按"可运维性验收"的标准过一遍：找一台干净机器（或删掉本地 `.oryxos/`），照着 README 从零走一遍——`java -jar oryxos.jar init`、配 API key 环境变量、配白名单和 webhook、创建两个 Agent、看它们跑起来。**新手 30 分钟内能走完**，这个版本才算能发；哪一步卡住了，说明缺的是文档不是代码。

**本节交付物**（Spec-Kit 拆解锚点）：

- Agent 定义：`weather-daily`（API 创建）与 `daily-tech-digest`（文件创建）的 Skill + Profile
- 外部依赖与白名单：`api.open-meteo.com`（天气）、团队 webhook 域名（notify）、新闻源——两条路二选一：`http_get` + 新闻源域名进白名单（稳，默认），或自写 `news-mcp`（实练，需提前连通）；启动只需 `DEEPSEEK_API_KEY`（26 节已排除 OpenAiAutoConfiguration）
- 发布物：fat JAR、`v0.1.0` tag、README 快速开始（30 分钟部署标准）

## 五、做完怎么验

- **Demo 一全绿**：连续两天 08:00 自动推送穿搭建议；审计对账不多不少；人推补跑与钟推行为一致。
- **Demo 二全绿**：连续两天 09:00 自动推送日报；内容体现记忆偏好；`GET /api/v1/tools` 里新闻 MCP 的工具在册；全程零 Java 代码。
- **双路径一致**：一个 Agent 走 API 创建、一个走文件创建，`.oryxos/` 里的产物格式一致，运行行为无差别。
- **版本可交付**：`mvn clean verify` 全绿；fat JAR 在干净机器上 30 分钟从零跑通；`v0.1.0` tag 已打。
- **演示彩蛋**：现场用管理平台改一下天气 Skill 的文风（比如"建议里加一句今日宜忌"），不重启，下一次触发就生效——把"声明式定义 + 即时生效"当着观众的面演出来。

到这里，第一阶段的目标物全部交付：一个能跑的 Agent OS 底座、一套定义 Agent 的机制、两个每天自己干活的真实 Agent、一个打了 tag 的版本。最后一节，我们把这五周走过的路、沉淀下来的方法，以及接下来往哪走，做一次收束。
