# 快速开始

五分钟跑起来第一个 OryxOS Agent。

## 前置条件

- **Java 21+**（必须，不支持低版本）
- 至少一个 LLM Provider 的 API Key

设置 API Key 环境变量（以 DeepSeek 为例）：

```bash
export DEEPSEEK_API_KEY=sk-your-api-key-here
```

建议将环境变量写入 `~/.zshrc` 或 `~/.bashrc`，避免每次重设。

## 安装

下载单可执行 JAR 文件：

```bash
# 下载（正式地址发布后更新）
curl -L https://github.com/oryxos/oryxos/releases/latest/download/oryxos.jar -o oryxos.jar

# 验证安装
java -jar oryxos.jar --version
```

> 目前为开发阶段，可从源码构建：`mvn clean package -DskipTests`，产物在 `oryxos-boot/target/`。

## 初始化工作区

在项目目录下执行：

```bash
oryxos init
```

命令会在当前目录创建 `.oryxos/` 工作区，结构如下：

```text
.oryxos/
├── profiles/           # Agent 配置文件（每个 Agent 一个 YAML）
├── memory/
│   └── MEMORY.md       # 长期记忆（由 Agent 写入，不要手动修改）
├── skills/             # SKILL.md 技能文件
├── sessions/           # 会话数据（备用）
├── logs/               # 结构化日志
├── mcp_servers.yaml    # MCP server 配置
├── oryxos.db           # SQLite 数据库
├── AGENTS.md           # Bootstrap：项目级 Agent 行为说明
├── SOUL.md             # Bootstrap：Agent 人格定义
└── USER.md             # Bootstrap：用户偏好（只读，Agent 不写）
```

初始化完成后，运行 `oryxos status` 确认工作区状态正常。

## 配置 Provider

编辑 `application.yml`：

```yaml
oryxos:
  providers:
    deepseek:
      api-key: ${DEEPSEEK_API_KEY}
      base-url: https://api.deepseek.com
      default-model: deepseek-chat
```

API Key 必须通过环境变量引用，不要将明文 Key 写入配置文件。支持同时配置多个 Provider，详见 [Provider 配置](/zh/docs/provider)。

## 创建第一个 Agent

在 `.oryxos/profiles/` 下创建 `my-agent.yaml`：

```yaml
name: my-agent
description: 我的第一个 Agent
identity:
  agent_name: 小欧
  prompt: 你是一个友好的助手，回答简洁清晰。
provider:
  name: deepseek
  model: deepseek-chat
```

五个字段就能让 Agent 跑起来。tools、skills、bootstrap 等字段按需添加，详见 [Profile 配置](/zh/docs/profile)。

## 开始对话

```bash
oryxos chat --profile my-agent
```

进入交互式对话界面：

```text
You: 介绍一下你自己

小欧 > 你好！我是小欧，一个友好的助手。有什么问题尽管问，我会尽力帮你解答。

You: /exit
```

使用 `/exit` 或 `Ctrl+C` 退出对话。

## 启动 API 服务

```bash
oryxos serve
```

默认监听 `8080` 端口，指定端口：

```bash
oryxos serve --port 9090
```

验证服务正常：

```bash
curl http://localhost:8080/api/v1/health
```

发起第一次 API 调用：

```bash
# 创建会话
curl -X POST http://localhost:8080/api/v1/sessions \
  -H "Content-Type: application/json" \
  -d '{"profile": "my-agent", "user_id": "user-001"}'

# 发消息（替换 {session_id} 为上一步返回的 ID）
curl -X POST http://localhost:8080/api/v1/sessions/{session_id}/messages \
  -H "Content-Type: application/json" \
  -d '{"content": "你好！"}'
```

## 下一步

- [OryxOS 是什么](/zh/docs/what) — 了解架构定位和设计理念
- [Profile 配置](/zh/docs/profile) — 完整的 Agent 配置参考
- [Tool 体系](/zh/docs/tool) — 内置 Tool 和扩展方式
- [REST API](/zh/docs/api) — 完整 API 文档
- [CLI 参考](/zh/docs/cli) — 所有命令行工具说明
