# 快速开始

三步启动 OryxOS。

## 前置条件

- Java 21+
- Maven 3.8+
- 至少一个 LLM Provider 的 API Key（DeepSeek、Qwen、OpenAI，或本地 Ollama 实例）

## 第一步：初始化工作区

```bash
oryxos init
```

这会在当前目录创建 `.oryxos/` 工作区，包含默认配置文件、`profiles/` 目录和 `MEMORY.md` 长期记忆文件。

## 第二步：配置 Profile

编辑 `.oryxos/profiles/default.yaml`，设置你的 LLM Provider：

```yaml
name: default
provider:
  name: deepseek
  model: deepseek-chat
  api_key: ${DEEPSEEK_API_KEY}
tools:
  - read_file
  - shell
  - http_get
  - save_memory
  - recall_memory
```

导出 API Key：

```bash
export DEEPSEEK_API_KEY=你的key
```

## 第三步：开始对话

```bash
oryxos chat
```

或者启动 REST API 服务：

```bash
oryxos serve --port 8080
```
