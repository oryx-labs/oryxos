# 快速开始

从源码构建、配置 Provider，并跑起来第一批 Agent。

## 前置条件

- **Java 21+**（必须，虚拟线程是运行时核心）
- **Maven 3.9+**（从源码构建）
- 至少一个 LLM Provider 的 API Key（DeepSeek、Qwen、Kimi 等）

## 构建

OryxOS 是 Maven 多模块项目，从源码构建可执行的 boot JAR：

```bash
mvn clean package -DskipTests
```

产物在 `oryxos-boot/target/`。下文用 `java -jar oryxos-boot/target/oryxos-boot-*.jar <命令>` 调用；示例里统一简写为 `oryxos <命令>`。

## 配置 Provider

Provider 凭证放在一个**外部**配置文件里，不纳入版本管理。复制随仓库提交的模板，填入你的真实 Key：

```bash
cp config/application.yml.example config/application.yml
```

`config/application.yml` 已在 `.gitignore`（切勿提交真实 Key）。启动脚本通过 `--spring.config.additional-location` 加载它；未覆盖的项从 JAR 内置的默认配置继承。

编辑 `config/application.yml`，为你使用的 Provider 填上 `api-key`。Provider 以列表形式声明在 `oryxos.providers` 下：

```yaml
oryxos:
  root: .oryxos
  providers:
    - name: deepseek
      api-key: ${DEEPSEEK_API_KEY}      # 环境变量占位符，启动时解析
      base-url: https://api.deepseek.com
    - name: mock                        # 内置假模型——无需 key/url，用于全链路自测
```

用 `${环境变量}` 占位符引用，不要把明文密钥写死。启动时，这里声明的 Provider 会被**播种（seed）进 SQLite 的 `providers` 表**（仅当尚不存在时）；此后数据库是权威来源，你也可以通过管理台或 `/api/v1/providers` 接口动态增删改 Provider——无需重启。

> Spring 对列表类键是**整体替换**而非合并。如果你在外部文件里覆盖 `oryxos.providers`，必须列全所有想保留的 Provider——只写一部分会把其余的丢掉。

## 初始化工作区

在你希望 OryxOS 运行的目录下执行 `init`，它会创建 `.oryxos/` 工作区：

```bash
oryxos init
```

创建的内容：

```text
.oryxos/
├── agents/             # 每个子目录 = 一个 Agent（AGENT.md + 可选 skills/ scripts/）
├── memory/             # 全局长期记忆（每个 Agent 的 MEMORY.md 在 agents/<name>/ 下）
├── sessions/           # 会话数据（备用；会话实际存 SQLite）
├── logs/               # 结构化 JSON 日志
├── AGENTS.md           # 项目级 Agent 行为说明
├── SOUL.md             # Agent 人格定义
└── USER.md             # 用户偏好（Agent 只读，不写）
```

SQLite 数据库（`oryxos.db`）在首次启动时于运行期创建。

### 可自定义工作区根目录

工作区默认为 `.oryxos`。若要指向其他位置：

- `config/application.yml` 中的 `oryxos.root`——对 Spring 启动的命令（`serve`、`gateway`）生效
- 环境变量 `ORYXOS_ROOT` 或 `-Doryxos.root=`——对轻命令（`init`、`status`、`profile`）生效，并通过 Spring 宽松绑定同时对启动型命令生效

配置的根目录会在运行期自动纳入文件沙箱白名单，改根不会破坏文件工具。

## 一个目录 = 一个 Agent

一个 Agent 就是 `.oryxos/agents/<name>/` 目录下的一个 `AGENT.md`。该文件 = YAML frontmatter（这个 Agent 的 Profile）+ Markdown 正文（注入 system prompt 的任务指令）。**不再有** `.oryxos/profiles/` 目录——Profile 就是 frontmatter。字段详见 [Profile 配置](/zh/docs/profile)。

OryxOS 在 `.oryxos/agents/` 下自带三个 Demo Agent 演示这套模型：

| Agent | 做什么 |
| --- | --- |
| `weather-daily` | 从 open-meteo 拿北京天气，通过 `notify` 推送穿搭建议 |
| `daily-tech-digest` | 从 `hn.algolia.com` 拉头条，按需读取 skill 文件，推送科技日报 |
| `github-daily` | 用 `shell` 跑 `python3 scripts/github_trending.py`，推送 GitHub 热门日报 |

三者都用 Provider `deepseek` / `deepseek-chat`，并按名引用通知渠道。

## 开始对话

```bash
oryxos chat --profile weather-daily
```

进入交互式对话界面。输入消息回车——Agent 跑一轮 ReAct 循环（思考、按需调用工具、回复）。输入 `exit` 或按 `Ctrl-D` 退出。

## 启动 API 服务

```bash
oryxos serve --port 8080
```

它在 `/api/v1` 下暴露 REST API，并在以下地址提供 **Web 管理台**：

```text
http://localhost:8080/admin/
```

管理台（Vue 3，视觉与本站同源）可管理 Agent、Provider、通知渠道、定时任务、会话、工具与沙箱白名单——在线创建和编辑 Agent，无需重启。快速健康检查：

```bash
curl http://localhost:8080/api/v1/health
```

```json
{ "code": 0, "message": "success", "data": { "status": "ok" }, "timestamp": 1720000000000 }
```

每个 API 响应都包裹在 `{ code, message, data, timestamp }` 信封里（`code: 0` 表示成功）。

## 下一步

- [OryxOS 是什么](/zh/docs/what) — 了解架构定位和设计理念
- [ReAct Loop](/zh/docs/react-loop) — 理解 think-act-observe 循环
- [Profile 配置](/zh/docs/profile) — 每个 AGENT.md frontmatter 字段说明
- [Tool 体系](/zh/docs/tool) — 内置 Tool 和扩展方式
- [REST API](/zh/docs/api) — 端点与请求/响应示例
- [CLI 参考](/zh/docs/cli) — 所有命令行工具说明
- [Memory 系统](/zh/docs/memory) — 长期记忆如何跨会话工作
