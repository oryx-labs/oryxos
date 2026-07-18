# 天气、科技日报、GitHub 日报 Agent 开发与演示：发布、打包、第一个版本

机制全部就位：底座跑得稳（27、28 节），能力能声明式装（29 节的 Skill）、Agent 能通过 API 动态管理（30 节）。这节是整个第一阶段的验收——做出**三个真实的业务 Agent**（每日天气、每日科技日报、每日 GitHub 日报），演示它们自己按点干活，然后打包发布第一个版本。三个 Demo 顺带把 29 节渐进式披露的三级（L1/L2/L3）与"能力可复用"各演到，跑通它们是核心功能发布的硬条件，也是"驾驭层"学成的证据。

---

## 一、这节要交付什么

一句话：**三个到点自动跑的 Agent + 一个打了 tag 的可部署版本。**

三个 Demo 是刻意挑过的，合起来把前面所有能力都压过一遍，也把 29 节渐进式披露的**三级 L1/L2/L3** 各演到一个，顺带演"**Agent（Profile）** 和 **Skill（能力）** 是两件事、且能力可复用"：

| | Demo 一：每日天气 | Demo 二：每日科技日报 | Demo 三：每日 GitHub 日报 |
|---|---|---|---|
| 场景 | 每天 08:00 查天气、穿搭建议、推群 | 每天 09:00 汇总科技新闻、推群，体现偏好 | 每天 09:30 GitHub 今日/本月热门 + AI 项目总结、推群 |
| 压到的能力 | Provider + ReAct + 内置 HTTP + Sandbox + 定时 + Notify | Skill **L1+L2**（`use_skill` 加载正文）+ MCP + Memory | Skill **L1+L2+L3**（`use_skill` 加载正文 + **跑捆绑脚本**）+ 沙箱信任边界 + Memory |
| Agent 怎么建 | 走 30 节的 API / 管理平台 | 走 29 节的手写文件 | 走 29 节的手写文件 |
| Skill 用到哪级 | **不用 Skill**——纯 Profile | **L2**（正文指令） | **L3**（捆绑可执行脚本） |

三个各证一件事：天气证明"一个纯 Profile 的 Agent 就能跑"、日报证明"能力被 Agent 按需加载正文（L2）"、GitHub 日报证明"能力能捆绑脚本、Agent 跑脚本拿确定性数据（L3）+ 一份能力可被多个 Agent 复用"。这就是 29 节架构的完整验收。

## 二、Demo 一：每日天气 Agent（纯 Profile，走 API 建）

**第一步：定义。** 天气这件事简单、不复用，**不需要做成 Skill**——指令直接写进 Agent 的 `identity.prompt`。在管理平台"新建 Agent"表单里填（或直接 `POST /api/v1/agents`）：

```json
{
  "name": "weather-daily",
  "description": "每天早上查北京天气并推送穿搭建议",
  "identity": {
    "agent_name": "天气小欧",
    "prompt": "你是天气助手。被触发时：\n1. 调用 http_get 请求 https://api.open-meteo.com/v1/forecast?latitude=39.9&longitude=116.4&current=temperature_2m,precipitation,wind_speed_10m 获取北京当前天气；\n2. 根据天气给出一段简短实用的穿搭建议；\n3. 把'今日天气 + 穿搭建议'组织成一条消息，调用 notify 发送出去。"
  },
  "provider": {"name": "deepseek", "model": "deepseek-chat"},
  "tools": ["http_get", "notify", "use_skill"],
  "notify_channels": [{"type": "webhook", "url": "${TEAM_WEBHOOK_URL}"}],
  "schedules": [{"id": "weather-morning", "cron": "0 0 8 * * *",
                 "zone": "Asia/Shanghai", "message": "到点了，按你的身份说明执行。"}]
}
```

天气源钉死用 **open-meteo**（免费、免 API key、返回 JSON），白名单里加 `api.open-meteo.com`；`notify_channels` 用 28 节配好的团队 webhook——前置条件清单这时兑现价值。不自己挑 API 的原因很实际：Demo 现场最怕"接口要注册账号 / 要充值 / 被墙"这类和课程无关的意外。（`use_skill` 是每个 Agent 默认自带的能力入口，天气 Agent 也有、只是用不上——最小权限体现在 `http_get`/`notify` 这些"能干活的"工具上。）

> **跑起来之前，先过三道环境门（不过就是空转，别急着等钟推）：**
> 1. **启动 key**：`export DEEPSEEK_API_KEY=...`。因为 26 节已排除 `OpenAiAutoConfiguration`，`serve` 只需要这一个 key 就能起——若它还索要 `spring.ai.openai.api-key`，说明 26 节那个排除没落地，回去补。
> 2. **白名单**（24 节 Sandbox 默认 deny-all，会拦自己）：`http.allowed_domains` 里必须有 `api.open-meteo.com` **和** 团队 webhook 的域名（飞书 `*.feishu.cn` / 企业微信 `qyapi.weixin.qq.com`，按实际渠道）。少一个，`tool_invocations` 就是一片 `success=false`。
> 3. **webhook 可达**：一个真能收消息的群机器人地址，配进 `notify_channels`。

**第二步：调试，先人推再钟推。** 别干等八点。先手动补跑一次把链路调通：

```bash
oryxos chat --profile weather-daily
> 到点了，按你的身份说明执行。
```

或者 `POST /agents/weather-daily/invoke`。人推和钟推走同一条链路（25 节验证过的），人推通了，钟推基本就通——这就是当初坚持"同一个 `AgentService` 入口"在调试体验上的回报。常见两类问题这一步就能暴露：天气 API 域名没进白名单（Sandbox 拦截，看 `tool_invocations` 的 `error_message`）、prompt 写得含糊导致模型不调工具（改 `identity.prompt`，用 30 节 `PUT` 覆写即时生效，再试）。

**第三步：等真正的钟推，对账。** 把 cron 临时改成几分钟后，看它完整自跑一轮：

![天气 Agent 端到端：定时触发、查天气、生成建议、推送](../../website/public/images/class-31-1.svg)

对账清单（跟需求文档的验收标准一字对齐）：无人触发；`http_get` 和 `notify` 两次涉外调用都过了白名单、都写进 `tool_invocations`；`llm_calls` 两条；`GET /api/v1/sessions/{id}` 能查到这次自动触发的完整对话；群里收到了消息。

## 三、Demo 二：每日科技日报 Agent（Agent + Skill，走手写文件）

这个 Demo 的重点是演**能力被按需加载**：把"怎么组一份科技日报"做成一个**可复用的 Skill 能力**，再建一个每天触发的 **Agent**，Agent 到点 `use_skill` 把组稿指令加载进来照做。

**第一步：搞定"拉科技新闻"这个外部能力——两条路，先保证能跑。** 这个 Agent 需要拉当日科技新闻，实现上两条路，按"Demo 必须跑起来"的优先级选：

- **路一（稳，建议默认）：直接用内置 `http_get`。** 找一个免 key、返回 JSON、稳定可达的新闻源（某科技媒体 RSS-to-JSON、或公开新闻聚合 JSON 接口），能力正文里让 LLM 调 `http_get` 取回、自己挑选组稿。Agent 的 `tools` 加 `http_get`、白名单加该新闻源域名即可——不引入外部进程，Demo 命运握在自己手里。
- **路二（MCP 实练，锦上添花）：声明一个新闻 MCP server。** 在 `.oryxos/mcp_servers.yaml` 里声明，重启后 `oryxos tool list` 能看到它暴露的工具。这是 20 节"方式二"的真实练手。**若走这条，务必提前把 `news-mcp` 写好、连通验证过，别 Demo 现场才写**——按 20 节方式二自己写一个最小 `news-mcp`（读两三个科技媒体 RSS、暴露一个 `fetch_tech_news` 返回标题列表，几十行任何语言都行），别赌社区某个 server 恰好活着；连不上时 MCP 会被 WARN 跳过（28 节"外部依赖不拖垮启动"），届时 Demo 就哑了。

两条路对能力正文透明（都是"调用新闻工具拉新闻"），差别只在 Agent 的 `tools`/`mcp_servers` 怎么给：路一给 `http_get` + 白名单域名，路二给 `mcp_servers` + 对应工具名。

**第二步：种一条偏好进记忆。** 先跟任意 Agent 聊一句：

```text
> 以后帮我关注科技新闻的话，我更关注 AI 和芯片方向。
```

模型判断值得长期记，调 `save_memory` 写进 `MEMORY.md`（22 节的机制）。确认文件里真的多了这一条再往下走。

**第三步：装能力（一个 Skill 目录）。** `.oryxos/skills/tech-digest/SKILL.md`——**frontmatter 只有 `name` + `description`**（29 节的能力形态，不含定时 / provider / 工具）：

```markdown
---
name: tech-digest
description: 把当日科技新闻组织成一份简明日报。当需要编写科技日报、汇总科技新闻时使用。
---

# 科技日报组稿

被要求编日报时：
1. 调用可用的新闻工具（`http_get` 拉新闻源，或新闻 MCP 工具）取当日科技新闻；
2. 挑最重要的 5~8 条，组织成一份简明日报；
3. 如果长期记忆里有用户关注方向的偏好，优先挑选并排列相关条目；
4. 调用 notify 把日报发送出去。
```

丢进目录、重启（或用 30 节 `POST /api/v1/skills` 上传）即进能力库。这份能力**不绑任何 Agent**，谁需要谁 `use_skill`。

**第四步：建 Agent（一份 Profile），到点自己跑。** `.oryxos/profiles/daily-tech-digest.yaml`：

```yaml
name: daily-tech-digest
description: 每天早上编一份科技日报推送到群
identity:
  agent_name: 日报小欧
  prompt: 你是科技日报编辑。被触发时，编写并推送今天的科技日报。
provider: {name: deepseek, model: deepseek-chat}
tools: [http_get, notify, use_skill]     # use_skill 才能加载 tech-digest 能力
notify_channels:
  - {type: webhook, url: ${TEAM_WEBHOOK_URL}}
schedules:
  - {id: digest-morning, cron: "0 0 9 * * *", zone: Asia/Shanghai,
     message: 到点了，编今天的科技日报。}
```

**全程零 Java 代码**——一份能力 markdown、一份 Profile YAML。注意分工：**Agent 只知道"编日报"（identity.prompt），"怎么编"在 `tech-digest` 能力里**，Agent 跑起来看到能力索引、`use_skill("tech-digest")` 把组稿步骤加载进来照做。这正是 29 节渐进式披露在真实场景里的样子。

**第五步：调试与对账。** 还是先人推补跑，再看钟推：

![日报 Agent 端到端：定时触发、use_skill 加载组稿能力、注入记忆、拉新闻、组稿、推送](../../website/public/images/class-31-2.svg)

对账重点比天气 Agent 多三条：**Agent 真的调了 `use_skill("tech-digest")` 把组稿指令加载进来**（渐进式披露生效的直接证据，`tool_invocations` 里有这条）；**日报内容真的把 AI / 芯片条目排在前面**——记忆在跨天场景里生效，也是最能打动人的一刻，因为这个偏好不在能力里、不在代码里，只在记忆里；**LLM 自己决定调新闻工具、自己组稿**——OryxOS 没有解析任何任务步骤。

## 四、Demo 三：每日 GitHub 日报 Agent（Agent + 能力 + L3 脚本）

这个 Demo 的重点是演**渐进式披露的第三级（L3）**：把"抓 GitHub 热门项目"这件确定性的活，做成能力里**捆绑的一个脚本**，Agent 到点 `use_skill` 加载正文、再跑脚本拿数据。它也是最"复杂"的一个：今日热门 + 本月热门 + 专挑 AI 相关项目做总结，还结合记忆偏好。

**第一步：装能力（一个带脚本的 Skill 目录）。** `.oryxos/skills/github-digest/` 目录：

`SKILL.md`（frontmatter 只有 `name` + `description`，正文让模型跑脚本、再组稿）：

```markdown
---
name: github-digest
description: 抓取 GitHub 今日与本月热门项目、专挑 AI 相关项目做总结。当需要编 GitHub 日报 / 周报、盘点热门开源项目时使用。
---

# GitHub 热门日报组稿

被要求编 GitHub 日报时：
1. 运行 `python scripts/github_trending.py`，它返回 JSON：`today`（今日新星项目）、`month`（本月新星项目）、`ai`（本月 AI 相关热门），每条含 name / stars / desc / url；
2. 组织成三段：**今日热门 Top5 / 本月热门 Top5 / 本月 AI 项目重点**；
3. 如果长期记忆里有用户关注的方向（如某类框架 / 主题），在"AI 项目重点"里优先排它相关的；
4. 调用 notify 把日报发送出去。
```

`scripts/github_trending.py`（**L3 捆绑脚本**，确定性抓数据、无需 key、零外部依赖）：

```python
import json, sys
from datetime import date, timedelta
from urllib.request import Request, urlopen
from urllib.parse import quote

API = "https://api.github.com/search/repositories"
HDR = {"User-Agent": "oryxos-github-digest", "Accept": "application/vnd.github+json"}

def search(q, n=5):
    url = f"{API}?q={quote(q)}&sort=stars&order=desc&per_page={n}"
    with urlopen(Request(url, headers=HDR), timeout=15) as r:
        items = json.load(r).get("items", [])
    return [{"name": i["full_name"], "stars": i["stargazers_count"],
             "desc": (i.get("description") or "")[:120], "url": i["html_url"]} for i in items]

today = date.today()
yday = (today - timedelta(days=1)).isoformat()
month0 = today.replace(day=1).isoformat()
out = {
    "today": search(f"created:>{yday}"),
    "month": search(f"created:>{month0}"),
    "ai":    search(f"created:>{month0} topic:ai topic:machine-learning topic:llm"),
}
json.dump(out, sys.stdout, ensure_ascii=False)
```

数据源钉死 **GitHub Search API**（免 token；未认证限速 10 次/分钟，日跑一次绰绰有余）。用"按创建日期区间 + stars 排序"近似 trending，避开没有官方 trending API 的坑。

> **L3 的信任边界，当着观众说清楚（29 节 §2.5）**：这个脚本自己发了网络请求到 `api.github.com`——它**绕过了 `http_get` 的域名白名单**（白名单只管内置 `http_get` 工具，管不到 `python` 子进程的网络）。所以**装 `github-digest` 这个带脚本的能力 = 信任写它的人**。核心阶段的沙箱对 L3 只做两道白名单：`shell.allowed_commands` 放行 `python`、`file.allowed_paths` 限定到这个 skill 的 `scripts/` 目录；把第三方能力关进受限容器 / 网络隔离是扩展阶段的事。这条对做 Agent OS 很关键：**能跑第三方能力很强，但信任从"底座"挪到了"能力作者"。**

**第二步：建 Agent（一份 Profile）。** `.oryxos/profiles/github-daily.yaml`：

```yaml
name: github-daily
description: 每天早上编一份 GitHub 热门 + AI 项目日报推送到群
identity:
  agent_name: 开源小欧
  prompt: 你是开源情报编辑。被触发时，编写并推送今天的 GitHub 热门日报。
provider: {name: deepseek, model: deepseek-chat}
tools: [shell, notify, use_skill]        # shell 用来跑 github_trending.py；use_skill 加载能力
notify_channels:
  - {type: webhook, url: ${TEAM_WEBHOOK_URL}}
schedules:
  - {id: github-morning, cron: "0 30 9 * * *", zone: Asia/Shanghai,
     message: 到点了，编今天的 GitHub 日报。}
```

沙箱前置：`shell.allowed_commands` 加 `python`、`http.allowed_domains` 加 `api.github.com`（脚本走的是子进程网络、不过 `http_get`，但把域名列进去便于审计与将来收口）、`file.allowed_paths` 覆盖 `.oryxos/skills/github-digest/scripts/`。

**第三步：种一条偏好进记忆。** 跟任意 Agent 聊一句"我更关注 Agent 框架和推理引擎方向的开源项目"，模型 `save_memory` 写进 `MEMORY.md`。

**第四步：调试与对账。** 先人推补跑（`oryxos chat --profile github-daily`），再看钟推。

![GitHub 日报 Agent 端到端：触发 → L1 索引 → use_skill 加载正文 → shell 跑脚本抓数据 → 组稿推送](../../website/public/images/class-31-4.svg)

对账重点，正好逐一钉住 29 架构的三级：

- **L1/L2**：`tool_invocations` 里有 `use_skill("github-digest")`——Agent 按 description 命中、把正文加载进来（渐进式披露生效）；
- **L3**：`tool_invocations` 里有 `shell` 跑 `python scripts/github_trending.py`——**确定性数据由脚本拿、代码不进上下文**，只有它返回的 JSON 进；
- **Memory**：日报"AI 项目重点"里，Agent 框架 / 推理引擎类项目排在前——记忆偏好在跨天场景里生效；
- **LLM 自主**：抓数据是脚本干的、组稿排序是 LLM 干的，OryxOS 没解析任何步骤。

**复用一把（29 节的立身之本）：** `github-digest` 是**能力、不是 Agent**，谁需要谁 `use_skill`。想再要一个"每周一发 GitHub 周报"的 Agent？**不用重写能力**——再建一份 Profile（`cron: "0 0 9 * * 1"`、prompt 改成"编本周 GitHub 周报"），照样 `use_skill("github-digest")`。**一份能力、两个 Agent 共享**——这才是"Skill = 可复用能力"的价值，也是它跟"一个 Skill 一个 Agent"的本质区别。

## 五、发布、打包、第一个版本

三个 Agent 都稳定自跑之后，把整个东西变成一个可交付的版本：

```bash
mvn clean verify              # 全量测试最后一道关
mvn clean package             # 产出 fat JAR
git tag v0.1.0 && git push --tags
```

![发布产物：一个 fat JAR + 一个 .oryxos 工作区（profiles + skills）](../../website/public/images/class-31-3.svg)

发布不只是打个包，要按"可运维性验收"的标准过一遍：找一台干净机器（或删掉本地 `.oryxos/`），照着 README 从零走一遍——`java -jar oryxos.jar init`、配 API key 环境变量、配白名单和 webhook、装 `tech-digest` / `github-digest` 两个能力、建三个 Agent、看它们跑起来。**新手 30 分钟内能走完**，这个版本才算能发；哪一步卡住了，说明缺的是文档不是代码。

**本节交付物**（Spec-Kit 拆解锚点）：

- Agent 定义：`weather-daily`（纯 Profile、API 创建）、`daily-tech-digest`（Profile + `tech-digest` 能力、文件创建）、`github-daily`（Profile + `github-digest` 能力含脚本、文件创建）
- 能力：`tech-digest`（L2，供日报 Agent）、`github-digest`（**L3，含 `scripts/github_trending.py`**，供 GitHub 日报 Agent、可被周报 Agent 复用）
- 外部依赖与白名单：`api.open-meteo.com`（天气）、团队 webhook 域名（notify）、新闻源（`http_get` 或自写 `news-mcp`）、`api.github.com`（GitHub 脚本）；沙箱 `shell.allowed_commands` 放行 `python`、`file.allowed_paths` 覆盖 skill 脚本目录；启动只需 `DEEPSEEK_API_KEY`（26 节已排除 OpenAiAutoConfiguration）
- 发布物：fat JAR、`v0.1.0` tag、README 快速开始（30 分钟部署标准）

## 六、做完怎么验

- **Demo 一全绿**：连续两天 08:00 自动推送穿搭建议；审计对账不多不少；人推补跑与钟推行为一致；纯 Profile、不碰能力。
- **Demo 二全绿（L2）**：连续两天 09:00 自动推送日报；`tool_invocations` 里有 `use_skill("tech-digest")`；内容体现记忆偏好；`GET /api/v1/skills` 里 `tech-digest` 在册；全程零 Java 代码。
- **Demo 三全绿（L3）**：连续两天 09:30 自动推送 GitHub 日报；`tool_invocations` 里有 `use_skill("github-digest")` **和** `shell` 跑 `python scripts/github_trending.py`；今日 / 本月 / AI 三段齐、AI 段体现记忆偏好；脚本产出的 JSON 进了上下文、脚本代码没进。
- **三级各演一个**：天气 = 纯 Profile（不用 Skill）、日报 = L2（use_skill 加载正文）、GitHub 日报 = L3（跑捆绑脚本）；`.oryxos/` 里 `profiles/` 与 `skills/` 各就各位。
- **能力可复用**：给 `github-digest` 再挂一个"周报"Profile（`cron` 改每周一），同一份能力被两个 Agent `use_skill`，行为一致——证明 Skill 是能力、不是 Agent。
- **双路径一致**：Agent 走 API 创建（天气）与走文件创建（日报 / GitHub），`.oryxos/` 里产物格式一致，运行行为无差别。
- **版本可交付**：`mvn clean verify` 全绿；fat JAR 在干净机器上 30 分钟从零跑通；`v0.1.0` tag 已打。
- **演示彩蛋**：现场改一下 `github-digest` 能力的正文（比如"AI 段每条加一句一句话点评"），不重启，下一次触发 `use_skill` 拿到的就是新版——把"声明式能力 + 即时生效"当着观众的面演出来。

到这里，第一阶段的目标物全部交付：一个能跑的 Agent OS 底座、一套装能力 / 定义 Agent 的机制、三个每天自己干活的真实 Agent（覆盖 Skill 的 L1/L2/L3 与能力复用）、一个打了 tag 的版本。最后一节，我们把这五周走过的路、沉淀下来的方法，以及接下来往哪走，做一次收束。
