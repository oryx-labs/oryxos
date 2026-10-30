<script setup>
import { computed } from 'vue'
import { useData, withBase } from 'vitepress'

const { lang } = useData()
const isZh = computed(() => lang.value === 'zh-CN')
const t = (zh, en) => isZh.value ? zh : en

const capabilities = computed(() => [
  {
    num: '01',
    title: t('Agent 与 Task Runtime', 'Agent & Task Runtime'),
    desc: t(
      '为企业应用提供一致的 Agent 执行、Task 生命周期、上下文、记忆、状态与交付物语义。应用关注业务，运行底座负责把工作可靠地跑起来。',
      'Gives enterprise applications consistent semantics for Agent execution, Task lifecycle, context, memory, state, and artifacts. Applications focus on business work; the platform runs it reliably.'
    ),
    code: `.oryxos/agents/ops-agent/
├── AGENT.md        # frontmatter + task body
├── skills/*.md     # on-demand sub-instructions
└── scripts/        # on-demand shell scripts

# Drop the dir in → WorkspaceWatcher registers it live
# Or: POST /api/v1/agents  (create / generate / edit)`,
  },
  {
    num: '02',
    title: t('模型路由与成本优化', 'Model Routing & Cost Optimization'),
    desc: t(
      '根据质量、时延、可用性、成本和数据边界选择模型。Provider 可动态接入与切换，路由、降级、预算、配额和成本归集逐步成为跨应用的共同能力。',
      'Selects models by quality, latency, availability, cost, and data boundaries. Providers can be added and switched dynamically; routing, fallback, budgets, quotas, and cost attribution evolve into shared capabilities across applications.'
    ),
    code: `# Providers are runtime-mutable, stored in SQLite
POST /api/v1/providers
{ "name": "deepseek", "apiKey": "\${DEEPSEEK_API_KEY}",
  "baseUrl": "https://api.deepseek.com" }

# An agent just references one by name
provider:
  name: deepseek        # → explicit ChatModel map
  model: deepseek-chat`,
  },
  {
    num: '03',
    title: t('知识与上下文', 'Knowledge & Context'),
    desc: t(
      '把知识库、检索、长期记忆与业务上下文组装为应用可复用的能力。上下文不再散落在每个业务应用中，也能持续演进。',
      'Packages knowledge bases, retrieval, long-term memory, and business-context assembly as reusable application capabilities instead of scattering them across every application.'
    ),
    code: `User message
  → PromptBuilder: system + memory + history + tools
  → ProviderService.call()
  → [Tool call?]
      → SandboxChecker whitelist
      → ToolExecutor.execute()
      → write tool_invocations audit
      → append result → loop
  → [Final reply] → return`,
  },
  {
    num: '04',
    title: t('Tool、MCP 与企业系统接入', 'Tools, MCP & Enterprise Integrations'),
    desc: t(
      '应用通过内置 Tool、MCP Server、Skill、渠道与自定义扩展连接模型之外的真实世界；这些能力被多个应用复用。',
      'Applications connect to the real world through built-in Tools, MCP servers, Skills, channels, and custom extensions that can be reused across applications.'
    ),
    code: `# Agent saves a preference
Tool: save_memory
Input: {"content": "Weekly report goes out every Friday 5pm"}

# Persisted per-agent, auto-injected next turn
# .oryxos/agents/ops-agent/MEMORY.md

Tool: recall_memory
Input: {"query": "user preferences"}`,
  },
  {
    num: '05',
    title: t('治理 Runtime', 'Governance Runtime'),
    desc: t(
      '身份、授权、策略、审批、沙箱边界和审计进入每一次模型与 Tool 行动的执行路径。应用可以组合能力，但不能绕过治理。',
      'Identity, authorization, policy, approval, sandbox boundaries, and audit travel with every model and Tool action. Applications can compose capabilities, but cannot bypass governance.'
    ),
    code: `# Whitelists, not SecurityManager
shell.allowed_commands: [ls, cat, python3]
http.allowed_domains:   ["*.github.com"]

# Manage at runtime
POST /api/v1/sandbox/whitelist/SHELL
# Extend via MCP server or native @Tool`,
  },
  {
    num: '06',
    title: t('运行、成本与可靠性', 'Operations, Cost & Reliability'),
    desc: t(
      '将 Trace、成本归集、评测、重试、恢复与可观测性放在统一底座中，让企业能够运行、观察并持续优化生产中的 AI 应用。',
      'Places traceability, cost attribution, evaluation, retries, recovery, and observability in one foundation so enterprises can run, observe, and continuously optimize production AI applications.'
    ),
    code: `❯ oryxos serve --port 8080

/api/v1/**     REST API (unified envelope)
/admin/        Web admin console
/swagger-ui    OpenAPI docs`,
  },
])

const scenarios = computed(() => [
  {
    num: '01',
    title: t('运维助手', 'DevOps Agent'),
    desc: t('读日志、执行 Shell、监控服务，跨对话记住你的运维偏好。', 'Reads logs, runs shell commands, monitors services. Remembers infra preferences across sessions.'),
  },
  {
    num: '02',
    title: t('零代码 PR 日报', 'Zero-code PR Digest'),
    desc: t('写一个 SKILL.md 接入 GitHub MCP server，自动生成每日 PR 摘要，零代码。', 'Write a SKILL.md and connect a GitHub MCP server — daily PR summaries with zero code.'),
  },
  {
    num: '03',
    title: t('客服助手', 'Customer Service'),
    desc: t('通过 REST API 接入客服渠道，记住历史交互，必要时触发人工升级。', 'Handles queries via REST API, recalls past interactions, escalates when needed.'),
  },
  {
    num: '04',
    title: t('知识管理助手', 'Knowledge Management'),
    desc: t('索引内部文档，回答问题，将学到的事实写入长期记忆。', 'Indexes internal docs, answers questions, persists learned facts to long-term memory.'),
  },
  {
    num: '05',
    title: t('代码审查 Agent', 'Code Review Agent'),
    desc: t('通过 MCP 审查 PR，评论 Issue，在记忆中追踪审查模式。', 'Reviews PRs via MCP, comments on issues, tracks review patterns in memory.'),
  },
  {
    num: '06',
    title: t('HR 助手', 'HR Assistant'),
    desc: t('回答 HR 问题、安排面试、检索政策文档，通过 REST API 集成企业系统。', 'Answers HR queries, schedules interviews, retrieves policy docs via REST API.'),
  },
  {
    num: '07',
    title: t('告警监控 Agent', 'Alert Monitor'),
    desc: t('轮询监控 API，用 LLM 分析异常，发送结构化报告。', 'Polls monitoring APIs, analyzes anomalies with LLM, sends structured reports.'),
  },
  {
    num: '08',
    title: t('企业研发自动化', 'Engineering Automation'),
    desc: t('把代码、知识、工具与研发流程组合成可治理的研发自动化应用。', 'Compose code, knowledge, tools, and engineering workflows into governed automation applications.'),
  },
])

const roadmapPhases = computed(() => [
  {
    phase: t('阶段一', 'Phase 1'),
    status: t('当前', 'CURRENT'),
    active: true,
    title: t('单节点 Runtime 内核', 'Single-node Runtime Kernel'),
    items: [
      t('Agent 与 Task 执行', 'Agent and Task execution'),
      t('知识、Tool 与 MCP', 'Knowledge, Tools, and MCP'),
      t('审计、沙箱与 API', 'Audit, sandbox, and APIs'),
    ],
  },
  {
    phase: t('阶段二', 'Phase 2'),
    status: t('规划中', 'PLANNED'),
    active: false,
    title: t('企业执行底座', 'Enterprise Execution Foundation'),
    items: [
      t('持久 Task 与恢复', 'Durable Tasks and recovery'),
      t('模型路由与成本优化', 'Model routing and cost optimization'),
      t('治理 Control Plane', 'Governance Control Plane'),
    ],
  },
  {
    phase: t('阶段三', 'Phase 3'),
    status: t('愿景', 'VISION'),
    active: false,
    title: t('跨应用持续优化', 'Cross-application Optimization'),
    items: [
      t('评测与运行反馈', 'Evaluation and operational feedback'),
      t('分布式基础与 Flow', 'Distributed foundation and Flows'),
      t('受治理的多 Agent 协作', 'Governed multi-Agent collaboration'),
    ],
  },
])

const flowColumns = computed(() => [
  {
    id: 'channels',
    label: t('接入渠道', 'Channels'),
    nodes: ['CLI (oryxos chat)', 'REST API', 'Gateway (daemon)'],
    highlight: false,
  },
  {
    id: 'react',
    label: t('ReAct 引擎', 'ReAct Engine'),
    nodes: ['PromptBuilder', 'ProviderService', 'ToolExecutor'],
    highlight: true,
  },
  {
    id: 'capabilities',
    label: t('能力层', 'Capabilities'),
    nodes: [t('工具体系 (24+)', 'Tool System (24+)'), t('记忆系统', 'Memory System'), 'MCP Client'],
    highlight: false,
  },
  {
    id: 'storage',
    label: t('持久化', 'Storage'),
    nodes: ['SQLite (sessions)', 'tool_invocations', 'llm_calls'],
    highlight: false,
  },
])
</script>

<template>
  <div class="home">

    <!-- ── HERO ── -->
    <section class="hero">
      <div class="hero-inner">
        <p class="hero-eyebrow">
          <span class="eyebrow-comment">// </span>{{ t('开源 · 私有部署 · Apache 2.0', 'open-source · self-hosted · Apache 2.0') }}
        </p>

        <h1 class="hero-headline">
          <span class="headline-tag">{{ t('企业 AI 应用的开源 Agent Harness OS', 'The Open-Source Agent Harness OS for Enterprise AI') }}</span><br>
          <span class="headline-white">{{ t('构建企业 AI 应用，', 'Build enterprise AI applications.') }}</span><br>
          <span class="headline-amber">{{ t('统一治理每一次 AI 行动。', 'Govern every AI action.') }}</span>
        </h1>

        <p class="hero-sub">
          {{ t(
            '企业团队在 OryxOS 上组合 Agent、模型、知识、Tool、MCP 与 Flow，构建自己的研发自动化、知识支持、工单协同和运维应用。OryxOS 提供共享 Runtime、治理、模型路由与成本优化；应用自由组合能力，但不能绕过治理。',
            'Enterprise teams compose Agents, models, knowledge, Tools, MCP, and Flows on OryxOS to build their own engineering automation, knowledge support, ticketing, and operations applications. OryxOS provides the shared Runtime, governance, model routing, and cost optimization; applications compose freely, but cannot bypass governance.'
          ) }}
        </p>

        <div class="hero-ctas">
          <a class="btn-primary" :href="withBase(t('/zh/docs/what', '/docs/what'))">
            {{ t('了解 OryxOS', 'Explore OryxOS') }}
            <svg width="14" height="14" viewBox="0 0 14 14" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M2 7h10M8 3l4 4-4 4"/></svg>
          </a>
          <a class="btn-ghost" href="https://github.com/oryx-labs/oryxos" target="_blank" rel="noopener">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="currentColor"><path d="M12 2C6.477 2 2 6.477 2 12c0 4.418 2.865 8.166 6.839 9.489.5.092.682-.217.682-.483 0-.237-.008-.868-.013-1.703-2.782.605-3.369-1.343-3.369-1.343-.454-1.158-1.11-1.466-1.11-1.466-.908-.62.069-.608.069-.608 1.003.07 1.531 1.032 1.531 1.032.892 1.53 2.341 1.088 2.91.832.092-.647.35-1.088.636-1.338-2.22-.253-4.555-1.113-4.555-4.951 0-1.093.39-1.988 1.029-2.688-.103-.253-.446-1.272.098-2.65 0 0 .84-.27 2.75 1.026A9.564 9.564 0 0112 6.844c.85.004 1.705.115 2.504.337 1.909-1.296 2.747-1.027 2.747-1.027.546 1.379.202 2.398.1 2.651.64.7 1.028 1.595 1.028 2.688 0 3.848-2.339 4.695-4.566 4.943.359.309.678.92.678 1.855 0 1.338-.012 2.419-.012 2.745 0 .268.18.58.688.482A10.019 10.019 0 0022 12c0-5.523-4.477-10-10-10z"/></svg>
            GitHub
          </a>
        </div>

        <!-- Terminal Window -->
        <div class="terminal">
          <div class="terminal-titlebar">
            <span class="dot dot-red"></span>
            <span class="dot dot-yellow"></span>
            <span class="dot dot-green"></span>
            <span class="terminal-title">oryxos — bash</span>
          </div>
          <div class="terminal-body">
            <div class="term-line">
              <span class="term-prompt">❯</span>
            <span class="term-cmd">POST /tasks</span>
            </div>
            <div class="term-output">✓ Task accepted: release-diagnostics</div>
            <div class="term-output dim">  identity · business context · requested capability</div>
            <div class="term-spacer"></div>
            <div class="term-line">
              <span class="term-prompt">❯</span>
            <span class="term-cmd">oryxos runtime execute release-diagnostics</span>
            </div>
            <div class="term-output dim">Policy: production-readonly · approval required for changes</div>
            <div class="term-output dim">Model route: quality=balanced · cost=within budget</div>
            <div class="term-spacer"></div>
            <div class="term-line">
              <span class="term-user">task</span>
              <span class="term-msg">{{ t('定位本次发布后的异常并生成报告', 'Diagnose post-release errors and produce a report') }}</span>
            </div>
            <div class="term-spacer"></div>
            <div class="term-output agent-label">{{ t('[runtime] 受治理执行中...', '[runtime] Governed execution...') }}</div>
            <div class="term-output dim">  → Knowledge: release runbook</div>
            <div class="term-output dim">  → Tool: observability query</div>
            <div class="term-output dim">  → Policy + sandbox: ✓ allowed</div>
            <div class="term-spacer"></div>
            <div class="term-output agent-label">{{ t('[audit] 执行证据已记录', '[audit] Execution evidence recorded') }}</div>
            <div class="term-output">{{ t('报告已生成；模型、Tool、成本与 Trace 均可追溯。', 'Report ready; model, Tool, cost, and trace are all attributable.') }}</div>
            <div class="term-spacer"></div>
            <div class="term-line">
              <span class="term-prompt">❯</span>
              <span class="term-cursor"></span>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── STATS BAR ── -->
    <div class="stats-bar">
      <div class="stats-inner">
        <div class="stat">
          <span class="stat-num">01</span>
          <span class="stat-label">{{ t('共享 Runtime', 'shared Runtime') }}</span>
        </div>
        <div class="stat-divider"></div>
        <div class="stat">
          <span class="stat-num">02</span>
          <span class="stat-label">{{ t('统一治理', 'governed actions') }}</span>
        </div>
        <div class="stat-divider"></div>
        <div class="stat">
          <span class="stat-num">03</span>
          <span class="stat-label">{{ t('模型路由', 'model routing') }}</span>
        </div>
        <div class="stat-divider"></div>
        <div class="stat">
          <span class="stat-num">04</span>
          <span class="stat-label">{{ t('成本优化', 'cost optimization') }}</span>
        </div>
        <div class="stat-divider"></div>
        <div class="stat">
          <span class="stat-num">05</span>
          <span class="stat-label">{{ t('开放扩展', 'open extensions') }}</span>
        </div>
      </div>
    </div>

    <!-- ── HOW IT WORKS ── -->
    <section class="section section-dark">
      <div class="section-inner">
        <div class="section-header">
          <span class="section-label">{{ t('整体架构', 'ARCHITECTURE') }}</span>
          <h2 class="section-h2">{{ t('企业应用构建在统一运行与治理底座之上。', 'Enterprise applications, one shared runtime and governance foundation.') }}</h2>
        </div>

        <div class="arch-diagram">
          <img :src="withBase(t('/images/oryxos-overview-zh.svg', '/images/oryxos-overview-en.svg'))" :alt="t('OryxOS 整体架构', 'OryxOS architecture overview')" class="arch-img"/>
        </div>
      </div>
    </section>

    <!-- ── CORE CAPABILITIES ── -->
    <section class="section section-dark">
      <div class="section-inner">
        <div class="section-header">
          <span class="section-label">{{ t('核心能力', 'CORE CAPABILITIES') }}</span>
          <h2 class="section-h2">{{ t('应用负责业务，OryxOS 负责让能力可组合、行动可治理。', 'Applications own the business. OryxOS makes capabilities composable and actions governable.') }}</h2>
        </div>

        <div class="caps-grid">
          <div v-for="cap in capabilities" :key="cap.num" class="cap-card">
            <div class="cap-top">
              <span class="cap-num">{{ cap.num }}</span>
              <h3 class="cap-title">{{ cap.title }}</h3>
              <p class="cap-desc">{{ cap.desc }}</p>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── USE CASES ── -->
    <section class="section section-dark section-use-cases">
      <div class="section-inner">
        <div class="section-header">
          <span class="section-label">{{ t('使用场景', 'USE CASES') }}</span>
          <h2 class="section-h2">{{ t('企业真实场景', 'Enterprise-ready scenarios') }}</h2>
        </div>

        <div class="cases-grid">
          <div v-for="s in scenarios" :key="s.num" class="case-card">
            <span class="case-num">{{ s.num }}</span>
            <h3 class="case-title">{{ s.title }}</h3>
            <p class="case-desc">{{ s.desc }}</p>
          </div>
        </div>
      </div>
    </section>

    <!-- ── ROADMAP ── -->
    <section class="section section-dark section-roadmap">
      <div class="section-inner">
        <div class="section-header">
          <span class="section-label">{{ t('路线图', 'ROADMAP') }}</span>
          <h2 class="section-h2">{{ t('先把共同运行语义做稳，再让能力跨应用积累。', 'Stabilize shared runtime semantics first, then let capabilities compound across applications.') }}</h2>
        </div>

        <div class="roadmap-grid">
          <div v-for="p in roadmapPhases" :key="p.phase" class="roadmap-card" :class="{ 'roadmap-card--active': p.active }">
            <div class="roadmap-top">
              <span class="roadmap-phase">{{ p.phase }}</span>
              <span class="roadmap-status" :class="{ 'roadmap-status--active': p.active }">{{ p.status }}</span>
            </div>
            <h3 class="roadmap-title">{{ p.title }}</h3>
            <ul class="roadmap-items">
              <li v-for="item in p.items" :key="item" class="roadmap-item">{{ item }}</li>
            </ul>
          </div>
        </div>
      </div>
    </section>

    <!-- ── CTA ── -->
    <section class="section section-cta">
      <div class="section-inner">
        <div class="cta-grid">
          <div class="cta-left">
            <span class="section-label label-dark">{{ t('立即开始', 'GET STARTED') }}</span>
            <h2 class="cta-h2">{{ t('为企业构建自己的 AI 应用运行底座。', 'Build the operating foundation for your enterprise AI applications.') }}</h2>
            <p class="cta-sub">{{ t('从一个受治理的应用开始，将模型、知识、Tool 与企业系统组合起来；治理、成本与运行证据由底座统一承接。', 'Start with one governed application. Compose models, knowledge, Tools, and enterprise systems while the platform carries governance, cost, and execution evidence.') }}</p>
            <div class="cta-btns">
              <a class="btn-dark" :href="withBase(t('/zh/docs/what', '/docs/what'))">{{ t('查看文档', 'Read the Docs') }}</a>
              <a class="btn-dark-ghost" href="https://github.com/oryx-labs/oryxos" target="_blank" rel="noopener">GitHub</a>
            </div>
          </div>
          <div class="cta-right">
            <div class="cta-terminal">
              <div class="cta-terminal-bar">
                <span class="dot dot-dark"></span>
                <span class="dot dot-dark"></span>
                <span class="dot dot-dark"></span>
              </div>
              <pre class="cta-code"><code><span class="code-comment"># 1. {{ t('构建并启动 Runtime', 'Build and start the Runtime') }}</span>
<span class="code-prompt">❯</span> mvn package -DskipTests
<span class="code-prompt">❯</span> bin/start.sh

<span class="code-comment"># 2. {{ t('组合你的业务能力', 'Compose business capabilities') }}</span>
<span class="code-prompt">❯</span> Agent + Knowledge + Tools + MCP + Flow

<span class="code-comment"># 3. {{ t('运行并治理每一次行动', 'Run and govern every action') }}</span>
<span class="code-prompt">❯</span> Task → Policy → Model / Tool → Audit</code></pre>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── FOOTER ── -->
    <footer class="footer">
      <div class="footer-inner">
        <div class="footer-brand">
          <span class="footer-logo">Oryx<strong>OS</strong></span>
          <span class="footer-tagline">{{ t('企业 AI 应用的开源 Agent Harness OS · 私有部署', 'Open-source Agent Harness OS for enterprise AI applications · Self-hosted') }}</span>
        </div>
        <div class="footer-links">
          <a :href="withBase(t('/zh/docs/what', '/docs/what'))" class="footer-link">{{ t('文档', 'Docs') }}</a>
          <a href="https://github.com/oryx-labs/oryxos" target="_blank" rel="noopener" class="footer-link">GitHub</a>
        </div>
      </div>
    </footer>

  </div>
</template>

<style scoped>
/* ────────────────────────────────────────────────
   RESET / BASE
──────────────────────────────────────────────── */
.home {
  min-height: 100vh;
  background: #000000;
  color: #fafafa;
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;
  /* Override VitePress default page padding */
  margin: 0;
  padding: 0;
}
.home * { box-sizing: border-box; }
a { text-decoration: none; }

/* ────────────────────────────────────────────────
   HERO
──────────────────────────────────────────────── */
.hero {
  background: #000000;
  padding: 96px 24px 80px;
  text-align: center;
}
.hero-inner {
  max-width: 800px;
  margin: 0 auto;
  display: flex;
  flex-direction: column;
  align-items: center;
}

.hero-eyebrow {
  font-family: 'JetBrains Mono', 'Fira Code', monospace;
  font-size: 13px;
  color: #888888;
  margin: 0 0 32px;
  letter-spacing: 0.02em;
}
.eyebrow-comment { color: #f97316; }

.hero-headline {
  font-size: clamp(40px, 7vw, 72px);
  font-weight: 900;
  line-height: 1.05;
  letter-spacing: -0.03em;
  margin: 0 0 28px;
}
.headline-tag {
  display: inline-block;
  font-size: 0.38em;
  font-weight: 600;
  letter-spacing: 0.12em;
  text-transform: uppercase;
  color: #f97316;
  border: 1px solid rgba(249, 115, 22, 0.5);
  border-radius: 4px;
  padding: 3px 10px;
  margin-bottom: 12px;
  vertical-align: middle;
}
.headline-white { color: #fafafa; }
.headline-amber { color: #f97316; }

.hero-sub {
  font-size: 16px;
  line-height: 1.75;
  color: #888888;
  max-width: 620px;
  margin: 0 0 40px;
}

/* CTA Buttons */
.hero-ctas {
  display: flex;
  gap: 12px;
  flex-wrap: wrap;
  justify-content: center;
  margin-bottom: 56px;
}
.btn-primary {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 12px 28px;
  border-radius: 6px;
  background: #f97316;
  color: #000000;
  font-weight: 700;
  font-size: 14px;
  letter-spacing: 0.01em;
  transition: background 0.15s, transform 0.15s;
}
.btn-primary:hover { background: #fb923c; transform: translateY(-1px); }
.btn-ghost {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 12px 24px;
  border-radius: 6px;
  border: 1px solid #333333;
  color: #fafafa;
  font-weight: 600;
  font-size: 14px;
  transition: border-color 0.15s, color 0.15s;
}
.btn-ghost:hover { border-color: #f97316; color: #f97316; }

/* Terminal */
.terminal {
  width: 100%;
  max-width: 680px;
  border-radius: 10px;
  border: 1px solid #1e1e1e;
  background: #0a0a0a;
  overflow: hidden;
  text-align: left;
  box-shadow: 0 32px 80px rgba(0,0,0,0.8), 0 0 0 1px #1e1e1e;
}
.terminal-titlebar {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 12px 16px;
  background: #111111;
  border-bottom: 1px solid #1e1e1e;
}
.dot {
  width: 12px;
  height: 12px;
  border-radius: 50%;
  flex-shrink: 0;
}
.dot-red    { background: #ff5f57; }
.dot-yellow { background: #febc2e; }
.dot-green  { background: #28c840; }
.terminal-title {
  font-family: 'JetBrains Mono', 'Fira Code', monospace;
  font-size: 12px;
  color: #444444;
  margin-left: 8px;
}
.terminal-body {
  padding: 20px 20px 24px;
  font-family: 'JetBrains Mono', 'Fira Code', monospace;
  font-size: 13px;
  line-height: 1.7;
}
.term-line { display: flex; align-items: baseline; gap: 8px; }
.term-prompt { color: #f97316; font-weight: 700; }
.term-cmd { color: #fafafa; }
.term-output { color: #d4d4d4; padding-left: 0; }
.term-output.dim { color: #555555; }
.term-spacer { height: 6px; }
.term-user {
  color: #22c55e;
  font-weight: 700;
  flex-shrink: 0;
}
.term-msg { color: #fafafa; }
.agent-label { color: #f97316; font-weight: 700; }
.term-cursor {
  display: inline-block;
  width: 8px;
  height: 14px;
  background: #f97316;
  animation: blink 1.2s step-end infinite;
  vertical-align: text-bottom;
  margin-left: 2px;
}
@keyframes blink {
  0%, 100% { opacity: 1; }
  50%       { opacity: 0; }
}

/* ────────────────────────────────────────────────
   STATS BAR
──────────────────────────────────────────────── */
.stats-bar {
  background: #0d0d0d;
  border-top: 1px solid #1e1e1e;
  border-bottom: 1px solid #1e1e1e;
  padding: 0 24px;
}
.stats-inner {
  max-width: 900px;
  margin: 0 auto;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 28px 0;
}
.stat {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
  flex: 1;
}
.stat-num {
  font-family: 'JetBrains Mono', 'Fira Code', monospace;
  font-size: 28px;
  font-weight: 900;
  color: #f97316;
  line-height: 1;
}
.stat-label {
  font-size: 11px;
  color: #555555;
  text-align: center;
  letter-spacing: 0.03em;
}
.stat-divider {
  width: 1px;
  height: 40px;
  background: #1e1e1e;
  flex-shrink: 0;
}

/* ────────────────────────────────────────────────
   SECTIONS BASE
──────────────────────────────────────────────── */
.section { padding: 88px 24px; }
.section-inner { max-width: 1040px; margin: 0 auto; }
.section-dark { background: #000000; }
.section-light { background: #fafafa; }
.section-use-cases { border-top: 1px solid #1e1e1e; }

.section-header {
  text-align: center;
  margin-bottom: 56px;
}
.section-label {
  font-family: 'JetBrains Mono', 'Fira Code', monospace;
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.15em;
  text-transform: uppercase;
  color: #f97316;
  display: block;
  margin-bottom: 16px;
}
.label-dark { color: #000000; }
.section-h2 {
  font-size: clamp(26px, 4vw, 42px);
  font-weight: 800;
  color: #fafafa;
  margin: 0;
  letter-spacing: -0.02em;
  line-height: 1.1;
}
.section-h2.dark { color: #0a0a0a; }

/* ────────────────────────────────────────────────
   HOW IT WORKS — FLOW
──────────────────────────────────────────────── */
.flow {
  display: flex;
  align-items: flex-start;
  justify-content: center;
  gap: 40px;
  flex-wrap: nowrap;
  overflow-x: auto;
}
.flow-col {
  position: relative;
  flex: 1;
  min-width: 160px;
  max-width: 220px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.flow-col-label {
  font-family: 'JetBrains Mono', 'Fira Code', monospace;
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.08em;
  text-transform: uppercase;
  color: #888888;
  text-align: center;
  margin-bottom: 4px;
}
.flow-col--highlight .flow-col-label { color: #f97316; }
.flow-nodes {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.flow-node {
  padding: 10px 14px;
  border-radius: 6px;
  border: 1px solid #d1d5db;
  background: #ffffff;
  color: #111111;
  font-size: 12px;
  text-align: center;
  font-family: 'JetBrains Mono', 'Fira Code', monospace;
  line-height: 1.4;
}
.flow-col--highlight .flow-node {
  border-color: #f97316;
  background: rgba(249,115,22,0.06);
  color: #1a1a1a;
}
.flow-col:not(:last-child)::after {
  content: '→';
  position: absolute;
  right: -22px;
  top: 48px;
  font-size: 20px;
  color: #9ca3af;
  font-family: 'JetBrains Mono', 'Fira Code', monospace;
}

/* ────────────────────────────────────────────────
   CORE CAPABILITIES
──────────────────────────────────────────────── */
.caps-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 1px;
  background: #1e1e1e;
  border: 1px solid #1e1e1e;
  border-radius: 12px;
  overflow: hidden;
}
.cap-card {
  background: #111111;
  padding: 32px 28px;
  display: flex;
  flex-direction: column;
  gap: 20px;
  transition: background 0.2s;
  cursor: default;
}
.cap-card:hover {
  background: #161616;
  box-shadow: inset 0 0 0 1px #f97316;
}
.cap-top { display: flex; flex-direction: column; gap: 10px; }
.cap-num {
  font-family: 'JetBrains Mono', 'Fira Code', monospace;
  font-size: 11px;
  font-weight: 700;
  color: #f97316;
  letter-spacing: 0.1em;
}
.cap-title {
  font-size: 18px;
  font-weight: 700;
  color: #fafafa;
  margin: 0;
  line-height: 1.2;
}
.cap-desc {
  font-size: 13px;
  color: #888888;
  line-height: 1.7;
  margin: 0;
}
.cap-code {
  background: #0a0a0a;
  border: 1px solid #1e1e1e;
  border-radius: 6px;
  padding: 16px;
  font-size: 12px;
  line-height: 1.65;
  color: #d4d4d4;
  overflow-x: auto;
  margin: 0;
  white-space: pre;
  flex: 1;
}
.cap-code code {
  font-family: 'JetBrains Mono', 'Fira Code', monospace;
  background: none;
  color: inherit;
}

/* ────────────────────────────────────────────────
   USE CASES
──────────────────────────────────────────────── */
.cases-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 1px;
  background: #1e1e1e;
  border: 1px solid #1e1e1e;
  border-radius: 12px;
  overflow: hidden;
}
.case-card {
  background: #111111;
  padding: 28px 24px;
  display: flex;
  flex-direction: column;
  gap: 10px;
  border-left: 3px solid transparent;
  transition: border-color 0.2s, background 0.2s;
  cursor: default;
}
.case-card:hover {
  border-left-color: #f97316;
  background: #141414;
}
.case-num {
  font-family: 'JetBrains Mono', 'Fira Code', monospace;
  font-size: 11px;
  color: #444444;
  font-weight: 700;
  align-self: flex-end;
}
.case-title {
  font-size: 15px;
  font-weight: 700;
  color: #fafafa;
  margin: 0;
}
.case-desc {
  font-size: 12px;
  color: #666666;
  line-height: 1.65;
  margin: 0;
}

/* ────────────────────────────────────────────────
   CTA
──────────────────────────────────────────────── */
.section-cta {
  background: #f97316;
  padding: 88px 24px;
}
.cta-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 64px;
  align-items: center;
  max-width: 1040px;
  margin: 0 auto;
}
.cta-h2 {
  font-size: clamp(28px, 4vw, 48px);
  font-weight: 900;
  color: #000000;
  margin: 12px 0 16px;
  letter-spacing: -0.03em;
  line-height: 1.05;
}
.cta-sub {
  font-size: 15px;
  color: rgba(0,0,0,0.65);
  line-height: 1.7;
  margin: 0 0 32px;
}
.cta-btns { display: flex; gap: 12px; flex-wrap: wrap; }
.btn-dark {
  display: inline-flex;
  align-items: center;
  padding: 12px 24px;
  border-radius: 6px;
  background: #000000;
  color: #fafafa;
  font-weight: 700;
  font-size: 14px;
  transition: background 0.15s;
}
.btn-dark:hover { background: #111111; }
.btn-dark-ghost {
  display: inline-flex;
  align-items: center;
  padding: 12px 24px;
  border-radius: 6px;
  border: 2px solid rgba(0,0,0,0.25);
  color: #000000;
  font-weight: 700;
  font-size: 14px;
  transition: border-color 0.15s;
}
.btn-dark-ghost:hover { border-color: #000000; }
.cta-terminal {
  border-radius: 10px;
  border: 1px solid rgba(0,0,0,0.15);
  background: #0a0a0a;
  overflow: hidden;
  box-shadow: 0 20px 60px rgba(0,0,0,0.4);
}
.cta-terminal-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 12px 16px;
  background: #111111;
  border-bottom: 1px solid #1e1e1e;
}
.dot-dark { background: #333333; }
.cta-code {
  padding: 24px 20px;
  font-size: 13px;
  line-height: 1.75;
  margin: 0;
  white-space: pre;
  overflow-x: auto;
}
.cta-code code {
  font-family: 'JetBrains Mono', 'Fira Code', monospace;
  background: none;
  color: #d4d4d4;
}
.code-comment { color: #555555; }
.code-prompt { color: #f97316; font-weight: 700; }

/* ────────────────────────────────────────────────
   FOOTER
──────────────────────────────────────────────── */
.footer {
  background: #000000;
  border-top: 1px solid #111111;
  padding: 32px 24px;
}
.footer-inner {
  max-width: 1040px;
  margin: 0 auto;
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.footer-brand { display: flex; flex-direction: column; gap: 4px; }
.footer-logo {
  font-size: 18px;
  font-weight: 400;
  color: #fafafa;
  letter-spacing: -0.01em;
}
.footer-logo strong { font-weight: 900; }
.footer-tagline {
  font-size: 12px;
  color: #444444;
}
.footer-links { display: flex; gap: 24px; }
.footer-link {
  font-size: 13px;
  color: #555555;
  transition: color 0.15s;
}
.footer-link:hover { color: #f97316; }

/* ────────────────────────────────────────────────
   RESPONSIVE
──────────────────────────────────────────────── */
@media (max-width: 900px) {
  .caps-grid { grid-template-columns: 1fr; }
  .cases-grid { grid-template-columns: repeat(2, 1fr); }
  .cta-grid { grid-template-columns: 1fr; gap: 40px; }
}

@media (max-width: 768px) {
  .hero { padding: 72px 20px 64px; }
  .hero-headline { font-size: clamp(36px, 10vw, 56px); }
  .section { padding: 64px 20px; }
  .stats-inner { flex-wrap: wrap; gap: 24px; justify-content: center; }
  .stat-divider { display: none; }
  .stat { flex: none; width: 80px; }
  .flow { gap: 0; overflow-x: auto; }
  .flow-col { min-width: 130px; }
  .caps-grid { grid-template-columns: 1fr; }
  .cases-grid { grid-template-columns: 1fr; }
  .cta-grid { grid-template-columns: 1fr; }
  .footer-inner { flex-direction: column; gap: 20px; text-align: center; }
  .footer-links { justify-content: center; }
}

@media (max-width: 480px) {
  .hero-ctas { flex-direction: column; align-items: center; }
  .btn-primary, .btn-ghost { width: 200px; justify-content: center; }
}

/* ────────────────────────────────────────────────
   HOW IT WORKS — SUB PARAGRAPH
──────────────────────────────────────────────── */
.how-sub {
  font-size: 14px;
  line-height: 1.75;
  color: #666666;
  text-align: center;
  max-width: 680px;
  margin: -32px auto 48px;
}
.arch-diagram {
  width: 100%;
  margin-top: 8px;
  overflow-x: auto;
}
.arch-img {
  display: block;
  width: 100%;
  max-width: 960px;
  margin: 0 auto;
  border-radius: 10px;
}

/* ────────────────────────────────────────────────
   ROADMAP
──────────────────────────────────────────────── */
.section-roadmap { border-top: 1px solid #1e1e1e; }

.roadmap-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 1px;
  background: #1e1e1e;
  border: 1px solid #1e1e1e;
  border-radius: 12px;
  overflow: hidden;
}

.roadmap-card {
  background: #111111;
  padding: 32px 28px;
  display: flex;
  flex-direction: column;
  gap: 16px;
  border-left: 3px solid transparent;
  transition: background 0.2s;
}

.roadmap-card--active {
  border-left-color: #f97316;
  background: #141414;
}

.roadmap-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.roadmap-phase {
  font-family: 'JetBrains Mono', 'Fira Code', monospace;
  font-size: 11px;
  font-weight: 700;
  color: #f97316;
  letter-spacing: 0.1em;
  text-transform: uppercase;
}

.roadmap-status {
  font-family: 'JetBrains Mono', 'Fira Code', monospace;
  font-size: 10px;
  font-weight: 700;
  letter-spacing: 0.1em;
  text-transform: uppercase;
  color: #444444;
  padding: 3px 8px;
  border: 1px solid #333333;
  border-radius: 4px;
}

.roadmap-status--active {
  color: #f97316;
  border-color: #f97316;
  background: rgba(249, 115, 22, 0.08);
}

.roadmap-title {
  font-size: 17px;
  font-weight: 700;
  color: #fafafa;
  margin: 0;
  line-height: 1.25;
}

.roadmap-items {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.roadmap-item {
  font-size: 13px;
  color: #666666;
  line-height: 1.5;
  padding-left: 16px;
  position: relative;
}

.roadmap-item::before {
  content: '—';
  position: absolute;
  left: 0;
  color: #333333;
  font-family: 'JetBrains Mono', 'Fira Code', monospace;
}

@media (max-width: 900px) {
  .roadmap-grid { grid-template-columns: 1fr; }
}
</style>
