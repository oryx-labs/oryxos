# Quick Start

Get OryxOS running in minutes with three simple steps.

## Prerequisites

- Java 21+
- Maven 3.8+
- An API key for at least one LLM provider (DeepSeek, Qwen, OpenAI, or a local Ollama instance)

## Step 1: Initialize the workspace

```bash
oryxos init
```

This creates a `.oryxos/` workspace in the current directory with default configuration files, including a `profiles/` directory and a `MEMORY.md` long-term memory file.

## Step 2: Configure a Profile

Edit `.oryxos/profiles/default.yaml` to set your LLM provider:

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

Export your API key:

```bash
export DEEPSEEK_API_KEY=your-key-here
```

## Step 3: Start chatting

```bash
oryxos chat
```

Or launch the REST API server:

```bash
oryxos serve --port 8080
```
