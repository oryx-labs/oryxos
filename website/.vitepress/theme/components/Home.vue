<script setup>
import { computed } from 'vue'
import { useData } from 'vitepress'

const { lang } = useData()
const isZh = computed(() => lang.value === 'zh-CN')
const t = (zh, en) => isZh.value ? zh : en

const capabilities = computed(() => [
  {
    icon: '🔀',
    title: t('多模型路由', 'Multi-Provider LLM Routing'),
    subtitle: t('DeepSeek · Qwen · Kimi · OpenAI · Ollama · vLLM', 'DeepSeek · Qwen · Kimi · OpenAI · Ollama · vLLM'),
    code: `# .oryxos/profiles/ops-agent.yaml
provider:
  name: deepseek
  model: deepseek-chat
  api_key: \${DEEPSEEK_API_KEY}

# Switch provider at runtime — no code change
provider:
  name: qwen
  model: qwen-max

# Local model — data never leaves
provider:
  name: ollama
  model: qwen2.5:7b`,
  },
  {
    icon: '🔄',
    title: t('自实现 ReAct 循环', 'Self-implemented ReAct Loop'),
    subtitle: t('Reason → Act → Observe → 循环', 'Reason → Act → Observe → repeat'),
    code: `User message
  → PromptBuilder: system + memory + history + tools
  → ProviderService: ChatModel.call()
  → [Tool call?] → ToolExecutor → SandboxChecker
      → write tool_invocations audit
      → append result → loop (max_iterations)
  → [Final reply] → return to user`,
  },
  {
    icon: '🧠',
    title: t('三层记忆系统', 'Three-layer Memory'),
    subtitle: t('会话记忆 · 长期记忆 · 情景记忆（路线图）', 'Session · Long-term · Episodic (roadmap)'),
    code: `# Agent saves a preference to long-term memory
Tool: save_memory
Input: {"content": "User prefers Spring Boot over Spring MVC"}

# Agent recalls on next session
Tool: recall_memory
Input: {"query": "user preferences"}
Output: "User prefers Spring Boot over Spring MVC"

# Persisted in .oryxos/memory/MEMORY.md
# Injected into every system prompt automatically`,
  },
])

const scenarios = computed(() => [
  {
    num: '01',
    title: t('运维助手', 'DevOps Agent'),
    desc: t('读日志、执行 Shell、监控服务，跨对话记住你的运维偏好。', 'Reads logs, runs shell commands, monitors services. Remembers your infra preferences across sessions.'),
  },
  {
    num: '02',
    title: t('零代码 PR 日报', 'Zero-code PR Digest'),
    desc: t('写一个 SKILL.md 接入 GitHub MCP server，Agent 自动生成每日 PR 摘要，零 Java 代码。', 'Write a SKILL.md and point to a GitHub MCP server — Agent generates daily PR summaries with no Java code.'),
  },
  {
    num: '03',
    title: t('客服助手', 'Customer Service Agent'),
    desc: t('通过 REST API 接入客服渠道，记住历史交互，必要时触发人工升级。', 'Handles customer queries via REST API, recalls past interactions, escalates when needed.'),
  },
  {
    num: '04',
    title: t('知识管理助手', 'Knowledge Management'),
    desc: t('索引内部文档，回答问题，将学到的事实写入长期记忆。', 'Indexes internal docs, answers questions, saves learned facts to long-term memory.'),
  },
  {
    num: '05',
    title: t('代码审查 Agent', 'Code Review Agent'),
    desc: t('通过 MCP 审查 PR，评论 Issue，在记忆中追踪审查模式。', 'Reviews PRs via MCP, comments on issues, tracks review patterns in memory.'),
  },
  {
    num: '06',
    title: t('HR 助手', 'HR Assistant'),
    desc: t('回答 HR 问题、安排面试、检索政策文档，通过 REST API 集成企业系统。', 'Answers HR queries, schedules interviews, retrieves policy docs — all via REST API integration.'),
  },
  {
    num: '07',
    title: t('告警监控 Agent', 'Alert Monitor'),
    desc: t('轮询监控 API，用 LLM 分析异常，发送结构化报告。', 'Polls monitoring APIs, analyzes anomalies with LLM, sends structured reports.'),
  },
  {
    num: '08',
    title: t('多 Agent 协作', 'Multi-Agent Collaboration'),
    desc: t('多个 Agent 共享同一个 OryxOS 实例，各自拥有独立的 Profile、工具和记忆。', 'Multiple agents share the same OryxOS instance, each with its own profile, tools, and memory.'),
  },
])

const modules = [
  { name: 'oryxos-core', desc: t('核心抽象：OryxTool 接口、Session、ReActLoop、PromptBuilder、ToolExecutor', 'Core abstractions: OryxTool interface, Session, ReActLoop, PromptBuilder, ToolExecutor') },
  { name: 'oryxos-provider', desc: t('Provider 路由：ProviderService、Function Calling 适配、多 Provider 显式映射', 'Provider routing: ProviderService, Function Calling adapter, explicit multi-provider map') },
  { name: 'oryxos-memory', desc: t('记忆系统：MemoryService、LongTermMemory、MemoryTools（save/recall）', 'Memory system: MemoryService, LongTermMemory, MemoryTools (save/recall)') },
  { name: 'oryxos-tool', desc: t('工具体系：内置 Tool、MCP Client、ToolRegistry、SandboxChecker', 'Tool system: built-in tools, MCP Client, ToolRegistry, SandboxChecker') },
  { name: 'oryxos-channel-cli', desc: t('CLI Channel：oryxos chat 交互实现', 'CLI Channel: oryxos chat interactive implementation') },
  { name: 'oryxos-web', desc: t('Web 服务：10 个 REST 端点、ApiController、GlobalExceptionHandler', 'Web service: 10 REST endpoints, ApiController, GlobalExceptionHandler') },
  { name: 'oryxos-storage', desc: t('持久化：SQLite、SessionRepository、ToolInvocationRepository', 'Persistence: SQLite, SessionRepository, ToolInvocationRepository') },
  { name: 'oryxos-cli', desc: t('命令行入口：Picocli 主入口、12 个子命令、ConfigLoader', 'CLI entry: Picocli main, 12 sub-commands, ConfigLoader') },
  { name: 'oryxos-boot', desc: t('启动模块：Spring Boot 主类、自动配置、依赖聚合', 'Boot module: Spring Boot main class, auto-config, dependency aggregation') },
]
</script>

<template>
  <div class="oryxos-page">

    <!-- ── HERO ── -->
    <section class="oryxos-hero">
      <div class="oryxos-hero-inner">
        <div class="oryxos-badge">
          <span class="oryxos-badge-dot"></span>
          {{ t('企业级 Agent OS · Java 21 · Spring Boot 3', 'Enterprise Agent OS · Java 21 · Spring Boot 3') }}
        </div>

        <h1 class="oryxos-title">
          <span class="oryxos-title-name">OryxOS</span>
        </h1>

        <p class="oryxos-title-sub">{{ t('运行在你自己基础设施上的企业级 Agent OS', 'Run multiple AI agents on your own infrastructure') }}</p>

        <p class="oryxos-hero-desc">
          {{ t('OryxOS 是基于 Java 21 实现的企业级 Agent OS，装在你自己的 K8s 或服务器上，作为统一底座运行多个业务 Agent，共享渠道接入、模型路由、工具调用、记忆系统和沙箱执行能力。', 'OryxOS is an enterprise Agent OS built on Java 21. Deploy it on your own K8s or servers as a unified platform for multiple business agents — sharing channel access, LLM routing, tool execution, memory systems, and sandbox capabilities.') }}
        </p>

        <div class="oryxos-hero-actions">
          <a class="oryxos-btn-primary" :href="t('/zh/docs/what', '/docs/what')">
            {{ t('快速开始', 'Get Started') }} →
          </a>
          <a class="oryxos-btn-ghost" :href="t('/zh/docs/react-loop', '/docs/react-loop')">
            {{ t('架构设计', 'Architecture') }}
          </a>
          <a class="oryxos-btn-ghost" href="https://github.com/oryx-labs/oryxos" target="_blank" rel="noopener">
            GitHub
          </a>
        </div>

        <div class="oryxos-hero-note">
          {{ t('Java 21 Virtual Thread · Spring Boot 3 · SQLite · Spring AI Alibaba · Picocli · SnakeYAML', 'Java 21 Virtual Thread · Spring Boot 3 · SQLite · Spring AI Alibaba · Picocli · SnakeYAML') }}
        </div>
      </div>
    </section>

    <!-- ── PROBLEM ── -->
    <section class="oryxos-section">
      <div class="oryxos-section-inner">
        <div class="oryxos-problem">
          <div class="oryxos-problem-text">
            <h2 class="oryxos-section-title">{{ t('企业 Agent 的痛点', 'Enterprise Agent Pain Points') }}</h2>
            <p>{{ t('每个企业在落地 AI Agent 时，都会遇到同样的问题。', 'Every enterprise faces the same challenges when deploying AI agents.') }}</p>
            <p class="oryxos-problem-item">
              <strong>{{ t('① 模型供应商锁定', '① Vendor lock-in') }}</strong>
              {{ t('硬编码 LLM 接口，换模型就要改代码，本地模型无法接入。', 'Hardcoded LLM endpoints mean switching providers requires code changes and local models are excluded.') }}
            </p>
            <p class="oryxos-problem-item">
              <strong>{{ t('② 没有记忆，每次都从零开始', '② No memory — stateless by default') }}</strong>
              {{ t('Agent 无法跨对话记住用户偏好和历史上下文。', 'Agents forget everything between conversations — user preferences and context are lost.') }}
            </p>
            <p class="oryxos-problem-item">
              <strong>{{ t('③ 审计缺失，无法合规', '③ No audit trail') }}</strong>
              {{ t('工具调用和 LLM 请求没有落库，生产事故无法溯源。', 'Tool calls and LLM requests are not persisted — production incidents cannot be traced.') }}
            </p>
            <p class="oryxos-solution-line">{{ t('OryxOS 把模型路由、记忆、工具、审计整合在同一个 Java 底座里，让每个团队专注业务 Agent 逻辑。', 'OryxOS integrates LLM routing, memory, tools, and audit in one Java platform — letting each team focus on business agent logic.') }}</p>
          </div>
          <div class="oryxos-problem-compare">
            <div class="oryxos-compare-item oryxos-compare-bad">
              <div class="oryxos-compare-label">{{ t('今天的做法', "Today's approach") }}</div>
              <div class="oryxos-compare-rows">
                <div class="oryxos-compare-row">
                  <span class="oryxos-compare-icon">✗</span>
                  <span>{{ t('硬编码 LLM 接口，换模型改代码', 'Hardcoded LLM endpoints, code changes to switch') }}</span>
                </div>
                <div class="oryxos-compare-row">
                  <span class="oryxos-compare-icon">✗</span>
                  <span>{{ t('无跨对话记忆，每次从零开始', 'No cross-session memory, starts fresh every time') }}</span>
                </div>
                <div class="oryxos-compare-row">
                  <span class="oryxos-compare-icon">✗</span>
                  <span>{{ t('工具调用无审计，生产事故无法溯源', 'No tool audit — production incidents untraceable') }}</span>
                </div>
                <div class="oryxos-compare-row">
                  <span class="oryxos-compare-icon">✗</span>
                  <span>{{ t('每个团队自己造 Agent 基础设施', 'Every team rebuilds the same agent infrastructure') }}</span>
                </div>
              </div>
            </div>
            <div class="oryxos-compare-item oryxos-compare-good">
              <div class="oryxos-compare-label">OryxOS</div>
              <div class="oryxos-compare-rows">
                <div class="oryxos-compare-row">
                  <span class="oryxos-compare-icon oryxos-icon-ok">✓</span>
                  <span>{{ t('Profile YAML 切换 Provider，零代码', 'Switch LLM provider in Profile YAML — zero code') }}</span>
                </div>
                <div class="oryxos-compare-row">
                  <span class="oryxos-compare-icon oryxos-icon-ok">✓</span>
                  <span>{{ t('三层记忆系统，长期记忆自动注入 system prompt', 'Three-layer memory — long-term injected automatically') }}</span>
                </div>
                <div class="oryxos-compare-row">
                  <span class="oryxos-compare-icon oryxos-icon-ok">✓</span>
                  <span>{{ t('tool_invocations + llm_calls 审计表 Day One 写入', 'Audit tables written from day one — full traceability') }}</span>
                </div>
                <div class="oryxos-compare-row">
                  <span class="oryxos-compare-icon oryxos-icon-ok">✓</span>
                  <span>{{ t('统一底座，多 Agent 共享渠道和工具', 'Unified platform — multiple agents share channels and tools') }}</span>
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── CAPABILITIES ── -->
    <section class="oryxos-section oryxos-primitives-section">
      <div class="oryxos-section-inner oryxos-primitives-inner">
        <div class="oryxos-section-header">
          <div class="oryxos-section-tag">{{ t('核心能力', 'Core Capabilities') }}</div>
          <h2 class="oryxos-section-title">{{ t('模型路由 + ReAct 循环 + 记忆系统', 'LLM Routing + ReAct Loop + Memory System') }}</h2>
        </div>
        <div class="oryxos-primitives">
          <div v-for="p in capabilities" :key="p.title" class="oryxos-primitive">
            <div class="oryxos-primitive-header">
              <span class="oryxos-primitive-icon">{{ p.icon }}</span>
              <div>
                <h3 class="oryxos-primitive-title">{{ p.title }}</h3>
                <p class="oryxos-primitive-subtitle">{{ p.subtitle }}</p>
              </div>
            </div>
            <pre class="oryxos-code"><code>{{ p.code }}</code></pre>
          </div>
        </div>
      </div>
    </section>

    <!-- ── SCENARIOS ── -->
    <section class="oryxos-section">
      <div class="oryxos-section-inner">
        <div class="oryxos-section-header">
          <div class="oryxos-section-tag">{{ t('真实场景', 'Real Scenarios') }}</div>
          <h2 class="oryxos-section-title">{{ t('八个企业真实使用场景', 'Eight enterprise use cases') }}</h2>
        </div>
        <div class="oryxos-scenarios">
          <div v-for="s in scenarios" :key="s.num" class="oryxos-scenario">
            <div class="oryxos-scenario-num">{{ s.num }}</div>
            <div>
              <h3 class="oryxos-scenario-title">{{ s.title }}</h3>
              <p class="oryxos-scenario-desc">{{ s.desc }}</p>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── TECH STACK ── -->
    <section class="oryxos-section oryxos-modules-section">
      <div class="oryxos-section-inner">
        <div class="oryxos-section-header">
          <div class="oryxos-section-tag">{{ t('模块架构', 'Module Architecture') }}</div>
          <h2 class="oryxos-section-title">{{ t('九个 Maven 模块，接口解耦', 'Nine Maven modules, interface-decoupled') }}</h2>
          <p class="oryxos-section-desc">{{ t('新增 Channel 或 Tool 只需加新模块，不改 oryxos-core。', 'Adding a new Channel or Tool requires only a new module — oryxos-core stays untouched.') }}</p>
        </div>
        <div class="oryxos-modules">
          <div v-for="m in modules" :key="m.name" class="oryxos-module">
            <code class="oryxos-module-name">{{ m.name }}</code>
            <p class="oryxos-module-desc">{{ m.desc }}</p>
          </div>
        </div>
      </div>
    </section>

    <!-- ── CTA ── -->
    <section class="oryxos-section oryxos-cta-section">
      <div class="oryxos-section-inner">
        <div class="oryxos-cta">
          <h2 class="oryxos-cta-title">{{ t('开始构建你的企业 Agent OS', 'Start building your enterprise Agent OS') }}</h2>
          <p class="oryxos-cta-desc">{{ t('三步启动：初始化工作区、配置 Profile、开始对话。', 'Three steps: initialize the workspace, configure a Profile, start chatting.') }}</p>
          <pre class="oryxos-code oryxos-cta-code"><code># 1. Initialize the workspace
oryxos init

# 2. Configure your LLM provider
export DEEPSEEK_API_KEY=your-key-here

# 3. Start chatting with your agent
oryxos chat --profile ops-agent

# Or launch the REST API server
oryxos serve --port 8080</code></pre>
          <div class="oryxos-cta-links">
            <a class="oryxos-btn-primary" :href="t('/zh/docs/what', '/docs/what')">{{ t('查看文档', 'Read the Docs') }}</a>
            <a class="oryxos-btn-ghost" href="https://github.com/oryx-labs/oryxos" target="_blank" rel="noopener">GitHub</a>
          </div>
        </div>
      </div>
    </section>

  </div>
</template>

<style scoped>
.oryxos-page {
  min-height: 100vh;
  background: #ffffff;
  color: #000000;
  font-family: inherit;
}

/* ── Hero ── */
.oryxos-hero {
  position: relative;
  padding: 100px 24px 80px;
  text-align: center;
  overflow: hidden;
}
.oryxos-hero-inner {
  position: relative;
  max-width: 760px;
  margin: 0 auto;
  display: flex;
  flex-direction: column;
  align-items: center;
}
.oryxos-badge {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 6px 16px;
  border-radius: 20px;
  border: 1px solid #bfdbfe;
  background: #eff6ff;
  color: #1e3a8a;
  font-size: 12px;
  margin-bottom: 28px;
}
.oryxos-badge-dot {
  width: 6px; height: 6px;
  border-radius: 50%;
  background: #2563eb;
  animation: pulse 2s infinite;
}
@keyframes pulse {
  0%,100% { opacity:1; transform:scale(1); }
  50% { opacity:0.4; transform:scale(1.4); }
}
.oryxos-title {
  margin: 0 0 12px;
  line-height: 1;
}
.oryxos-title-name {
  font-size: clamp(64px, 12vw, 108px);
  font-weight: 900;
  letter-spacing: -0.03em;
  color: #0f172a;
  font-family: 'Space Grotesk', sans-serif;
}
.oryxos-title-sub {
  font-size: 18px;
  color: #475569;
  margin: 0 0 20px;
}
.oryxos-hero-desc {
  font-size: 16px;
  line-height: 1.7;
  color: #334155;
  max-width: 620px;
  margin: 0 0 32px;
}
.oryxos-hero-actions {
  display: flex;
  gap: 12px;
  flex-wrap: wrap;
  justify-content: center;
  margin-bottom: 20px;
}
.oryxos-btn-primary {
  padding: 11px 28px;
  border-radius: 8px;
  background: #1e3a8a;
  color: #ffffff;
  font-weight: 600;
  font-size: 14px;
  text-decoration: none;
  transition: opacity 0.2s, transform 0.15s;
}
.oryxos-btn-primary:hover { opacity: 0.85; transform: translateY(-1px); }
.oryxos-btn-ghost {
  padding: 11px 28px;
  border-radius: 8px;
  border: 1px solid #cbd5e1;
  color: #334155;
  font-weight: 600;
  font-size: 14px;
  text-decoration: none;
  transition: border-color 0.2s, background 0.2s;
}
.oryxos-btn-ghost:hover { border-color: #1e3a8a; background: #eff6ff; color: #1e3a8a; }
.oryxos-hero-note {
  font-size: 12px;
  color: #94a3b8;
}

/* ── Section ── */
.oryxos-section { padding: 72px 24px; }
.oryxos-section-inner { max-width: 1000px; margin: 0 auto; }
.oryxos-primitives-inner { max-width: 1400px; }
.oryxos-section-header { text-align: center; margin-bottom: 48px; }
.oryxos-section-tag {
  display: inline-block;
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.1em;
  text-transform: uppercase;
  color: #1d4ed8;
  padding: 4px 12px;
  border-radius: 20px;
  border: 1px solid #bfdbfe;
  background: #eff6ff;
  margin-bottom: 14px;
}
.oryxos-section-title {
  font-size: clamp(22px, 4vw, 32px);
  font-weight: 700;
  color: #0f172a;
  margin: 0 0 12px;
}
.oryxos-section-desc {
  font-size: 15px;
  color: #64748b;
  max-width: 600px;
  margin: 0 auto;
  line-height: 1.6;
}

/* ── Problem ── */
.oryxos-problem {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 48px;
  align-items: start;
}
.oryxos-problem-text p { color: #475569; line-height: 1.7; margin: 0 0 14px; font-size: 15px; }
.oryxos-problem-item strong { color: #0f172a; display: block; margin-bottom: 4px; }
.oryxos-solution-line { color: #1e3a8a !important; font-weight: 600; }
.oryxos-problem-compare { display: flex; flex-direction: column; gap: 16px; }
.oryxos-compare-item {
  padding: 20px;
  border-radius: 12px;
  border: 1px solid #e2e8f0;
}
.oryxos-compare-bad { background: #fafafa; }
.oryxos-compare-good { background: #eff6ff; border-color: #bfdbfe; }
.oryxos-compare-label { font-size: 11px; font-weight: 700; color: #94a3b8; margin-bottom: 12px; text-transform: uppercase; letter-spacing: 0.08em; }
.oryxos-compare-rows { display: flex; flex-direction: column; gap: 8px; }
.oryxos-compare-row { display: flex; align-items: flex-start; gap: 10px; font-size: 13px; color: #475569; line-height: 1.5; }
.oryxos-compare-icon { flex-shrink: 0; font-style: normal; color: #cbd5e1; font-weight: 700; width: 14px; }
.oryxos-icon-ok { color: #1d4ed8; }

/* ── Primitives ── */
.oryxos-primitives-section { background: #f8fafc; }
.oryxos-primitives { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); grid-auto-rows: 1fr; gap: 16px; }
.oryxos-primitive {
  padding: 20px;
  border-radius: 14px;
  border: 1px solid #e2e8f0;
  background: #ffffff;
  display: flex;
  flex-direction: column;
  gap: 12px;
  transition: border-color 0.2s, box-shadow 0.2s;
  min-width: 0;
  overflow: hidden;
}
.oryxos-primitive .oryxos-code { flex: 1; }
.oryxos-primitive:hover { border-color: #2563eb; box-shadow: 0 4px 16px rgba(37,99,235,0.08); }
.oryxos-primitive-header { display: flex; align-items: flex-start; gap: 12px; }
.oryxos-primitive-icon { font-size: 28px; flex-shrink: 0; }
.oryxos-primitive-title { font-size: 17px; font-weight: 700; color: #0f172a; margin: 0 0 2px; }
.oryxos-primitive-subtitle { font-size: 12px; color: #94a3b8; margin: 0; }
.oryxos-code {
  background: #f1f5f9;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  padding: 14px 16px;
  font-size: 12px;
  line-height: 1.6;
  color: #1e293b;
  overflow-x: auto;
  margin: 0;
  white-space: pre;
}
.oryxos-code code { font-family: 'JetBrains Mono', 'Fira Code', monospace; background: none; color: inherit; }

/* ── Scenarios ── */
.oryxos-scenarios { display: grid; grid-template-columns: repeat(2, 1fr); gap: 20px; }
.oryxos-scenario {
  display: flex;
  gap: 16px;
  padding: 20px;
  border-radius: 12px;
  border: 1px solid #e2e8f0;
  background: #fafafa;
  transition: border-color 0.2s;
}
.oryxos-scenario:hover { border-color: #bfdbfe; background: #eff6ff; }
.oryxos-scenario-num {
  font-size: 28px;
  font-weight: 900;
  color: #e2e8f0;
  line-height: 1;
  flex-shrink: 0;
  font-variant-numeric: tabular-nums;
}
.oryxos-scenario:hover .oryxos-scenario-num { color: #bfdbfe; }
.oryxos-scenario-title { font-size: 15px; font-weight: 600; color: #0f172a; margin: 0 0 6px; }
.oryxos-scenario-desc { font-size: 13px; color: #64748b; line-height: 1.6; margin: 0; }

/* ── Modules ── */
.oryxos-modules-section { background: #f8fafc; }
.oryxos-modules {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 12px;
}
.oryxos-module {
  background: #ffffff;
  border: 1px solid #e2e8f0;
  border-radius: 10px;
  padding: 16px 18px;
  transition: border-color 0.2s, box-shadow 0.2s;
}
.oryxos-module:hover { border-color: #2563eb; box-shadow: 0 2px 12px rgba(37,99,235,0.07); }
.oryxos-module-name {
  font-family: 'JetBrains Mono', 'Fira Code', monospace;
  font-size: 12px;
  font-weight: 700;
  color: #1d4ed8;
  background: #eff6ff;
  border: 1px solid #bfdbfe;
  border-radius: 5px;
  padding: 2px 8px;
  display: inline-block;
  margin-bottom: 8px;
}
.oryxos-module-desc { font-size: 12px; color: #64748b; line-height: 1.5; margin: 0; }

/* ── CTA ── */
.oryxos-cta-section { background: #0f172a; }
.oryxos-cta-section .oryxos-cta { text-align: center; max-width: 680px; margin: 0 auto; }
.oryxos-cta-title { font-size: 28px; font-weight: 700; color: #f8fafc; margin: 0 0 12px; }
.oryxos-cta-desc { font-size: 15px; color: #94a3b8; margin: 0 0 24px; }
.oryxos-cta-section .oryxos-code {
  background: #1e293b;
  border-color: #334155;
  color: #e2e8f0;
  text-align: left;
  margin-bottom: 28px;
}
.oryxos-cta-code { text-align: left; }
.oryxos-cta-links { display: flex; gap: 12px; justify-content: center; flex-wrap: wrap; }
.oryxos-cta-section .oryxos-btn-primary {
  background: #2563eb;
}
.oryxos-cta-section .oryxos-btn-ghost {
  border-color: #334155;
  color: #e2e8f0;
}
.oryxos-cta-section .oryxos-btn-ghost:hover {
  border-color: #2563eb;
  background: rgba(37,99,235,0.15);
  color: #93c5fd;
}

/* ── Responsive ── */
@media (max-width: 900px) {
  .oryxos-modules { grid-template-columns: repeat(2, 1fr); }
}
@media (max-width: 768px) {
  .oryxos-hero { padding: 72px 20px 60px; }
  .oryxos-problem { grid-template-columns: 1fr; }
  .oryxos-primitives { grid-template-columns: 1fr; }
  .oryxos-scenarios { grid-template-columns: 1fr; }
  .oryxos-modules { grid-template-columns: 1fr; }
  .oryxos-section { padding: 48px 20px; }
}
</style>
