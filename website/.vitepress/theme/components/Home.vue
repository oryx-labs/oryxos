<script setup>
import { computed } from 'vue'
import { useData, withBase } from 'vitepress'

const { lang } = useData()
const isZh = computed(() => lang.value === 'zh-CN')
const t = (zh, en) => isZh.value ? zh : en

const capabilities = computed(() => [
  {
    num: '01',
    title: t('一个目录 = 一个 Agent', 'One Directory = One Agent'),
    desc: t(
      '一个 Agent 就是一个目录：AGENT.md = frontmatter（profile）+ 正文（任务指令），可选 skills/、scripts/。REST 创建、一句话 LLM 生成，或直接拖入目录，WorkspaceWatcher 热加载即上线，无需重启。',
      'An agent is a directory: AGENT.md = frontmatter (its profile) + a body of instructions, plus optional skills/ and scripts/. Create via REST, generate from one sentence with an LLM, or drop in a directory — WorkspaceWatcher makes it live with no restart.'
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
    title: t('动态多模型路由', 'Dynamic Multi-Provider Routing'),
    desc: t(
      'Provider 存 SQLite，支持运行时 CRUD——随时增删改。Agent 不感知具体厂商，显式 name → ChatModel 映射保证路由正确；改 key/base-url 下次调用自动重建。',
      'Providers live in SQLite with runtime CRUD — add, edit, remove anytime. Agents are provider-agnostic; explicit name → ChatModel routing stays correct, and the model rebuilds when its key or base URL changes.'
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
    title: t('自实现 ReAct 循环', 'Self-implemented ReAct Loop'),
    desc: t(
      '完整掌控 Reason → Act → Observe 循环，同步执行 + Java 21 虚拟线程，不依赖 Spring AI Agent 抽象。Spring AI 只做协议转换与 @Tool schema 生成。',
      'Full control over Reason → Act → Observe — sync execution on Java 21 virtual threads, no Spring AI Agent abstractions. Spring AI is used only for protocol translation and @Tool schema generation.'
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
    title: t('分层记忆系统', 'Layered Memory System'),
    desc: t(
      '会话记忆 + 按 Agent 的长期记忆（.oryxos/agents/<name>/MEMORY.md，关键词检索、带时间戳）。自动注入每次 system prompt，Agent 跨会话保持一致，后续可无缝升级向量检索。',
      'Session memory plus per-agent long-term memory (.oryxos/agents/<name>/MEMORY.md, keyword search, timestamped). Auto-injected into every system prompt so agents stay consistent across sessions — with a clear upgrade path to vector search.'
    ),
    code: `# Agent saves a preference
Tool: save_memory
Input: {"content": "Prefers Spring Boot over MVC"}

# Persisted per-agent, auto-injected next turn
# .oryxos/agents/ops-agent/MEMORY.md

Tool: recall_memory
Input: {"query": "user preferences"}`,
  },
  {
    num: '05',
    title: t('沙箱工具 + MCP', 'Sandboxed Tools + MCP'),
    desc: t(
      '内置文件 / Shell / HTTP 工具走路径、命令、域名白名单（不用 SecurityManager），可运行时管理。三档插件：零代码 SKILL.md → 自定义 MCP server → 原生 @Tool。Notify 渠道按名寻址。',
      'Built-in file / shell / HTTP tools pass through path, command, and domain whitelists (no SecurityManager), manageable at runtime. Three-tier plugins: zero-code SKILL.md → custom MCP server → native @Tool. Notify channels addressed by name.'
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
    title: t('Web 管理台', 'Web Admin Console'),
    desc: t(
      '/admin/ 控制台（Vue 3 + Vite，与官网同源风格）：Agent 管理与一句话生成、Provider / Notify 渠道 CRUD、定时任务、工作区文件浏览、记忆查看。审计表 llm_calls、tool_invocations 第一天就写入。',
      'A Vue 3 + Vite console at /admin/ (same theme as this site): agent management with one-sentence generation, Provider / Notify-channel CRUD, scheduled tasks, workspace file browser, and memory views. Audit tables llm_calls and tool_invocations are written from day one.'
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
    desc: t('写一个 SKILL.md 接入 GitHub MCP server，自动生成每日 PR 摘要，零 Java 代码。', 'Write a SKILL.md and connect a GitHub MCP server — daily PR summaries with no Java code.'),
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
    title: t('多 Agent 协作', 'Multi-Agent Collaboration'),
    desc: t('多个 Agent 共享同一个 OryxOS 实例，各自拥有独立的 Profile、工具和记忆。', 'Multiple agents share one OryxOS instance, each with its own profile, tools, and memory.'),
  },
])

const roadmapPhases = computed(() => [
  {
    phase: t('阶段一', 'Phase 1'),
    status: t('当前', 'CURRENT'),
    active: true,
    title: t('单节点运行内核', 'Single-node Runtime Kernel'),
    items: [
      t('5 大核心能力', '5 core capabilities'),
      t('多 Agent 并存', 'Multi-agent on one node'),
      t('REST API 暴露', 'REST API exposure'),
      t('MCP 工具协议', 'MCP tool protocol'),
    ],
  },
  {
    phase: t('阶段二', 'Phase 2'),
    status: t('规划中', 'PLANNED'),
    active: false,
    title: t('分布式基础', 'Distributed Foundation'),
    items: [
      t('无状态节点设计', 'Stateless node design'),
      t('外部状态存储', 'External state store'),
      t('多副本水平扩展', 'Multi-replica horizontal scale'),
    ],
  },
  {
    phase: t('阶段三', 'Phase 3'),
    status: t('愿景', 'VISION'),
    active: false,
    title: t('跨节点 A2A 协作', 'Cross-node A2A Collaboration'),
    items: [
      t('Agent 发现与注册', 'Agent discovery & registry'),
      t('跨 Agent 任务委托', 'Cross-agent task delegation'),
      t('A2A 协议标准化', 'A2A protocol standard'),
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
          <span class="headline-tag">{{ t('分布式 Agent Harness OS', 'Distributed Agent Harness OS') }}</span><br>
          <span class="headline-white">{{ t('给每个 Agent 一套', 'A Production-grade Harness') }}</span><br>
          <span class="headline-amber">{{ t('生产级运行骨架', 'for Every Agent') }}</span>
        </h1>

        <p class="hero-sub">
          {{ t(
            'OryxOS 是基于 Java 21 构建的 Agent Harness OS。harness（运行骨架）是把模型变成能干活的 Agent 的那层脚手架——循环、工具与执行、上下文、记忆、沙箱、审计。OryxOS 给每个 Agent 同一套生产级 harness，并像操作系统调度进程一样运行一整队 Agent。私有部署在你自己的 K8s 或服务器上。',
            'OryxOS is an Agent Harness OS built on Java 21. A harness is the scaffolding that turns a model into a working agent — the loop, tools and their execution, context, memory, sandbox, and audit. OryxOS gives every agent the same production-grade harness and runs a fleet of them like an OS runs processes. Deploy privately on your own K8s or servers.'
          ) }}
        </p>

        <div class="hero-ctas">
          <a class="btn-primary" :href="t('/zh/docs/what', '/docs/what')">
            {{ t('快速开始', 'Get Started') }}
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
              <span class="term-cmd">oryxos init</span>
            </div>
            <div class="term-output">✓ Workspace initialized at .oryxos/</div>
            <div class="term-output dim">  agents/ · memory/ · sessions/ · logs/ · oryxos.db</div>
            <div class="term-spacer"></div>
            <div class="term-line">
              <span class="term-prompt">❯</span>
              <span class="term-cmd">oryxos chat --profile ops-agent</span>
            </div>
            <div class="term-output dim">Loaded profile: ops-agent (deepseek-chat)</div>
            <div class="term-output dim">Memory: 3 entries loaded from MEMORY.md</div>
            <div class="term-spacer"></div>
            <div class="term-line">
              <span class="term-user">you</span>
              <span class="term-msg">{{ t('检查一下 nginx 最近的错误日志', 'Check nginx error logs from the last hour') }}</span>
            </div>
            <div class="term-spacer"></div>
            <div class="term-output agent-label">{{ t('[ops-agent] 思考中...', '[ops-agent] Thinking...') }}</div>
            <div class="term-output dim">  → Tool: shell</div>
            <div class="term-output dim">  → Input: tail -n 100 /var/log/nginx/error.log | grep "$(date +%H)"</div>
            <div class="term-output dim">  → SandboxChecker: ✓ allowed</div>
            <div class="term-spacer"></div>
            <div class="term-output agent-label">{{ t('[ops-agent]', '[ops-agent]') }}</div>
            <div class="term-output">{{ t('过去 1 小时发现 3 个 502 错误，均来自 upstream backend:8080', 'Found 3 × 502 errors in the last hour, all from upstream backend:8080') }}</div>
            <div class="term-output">{{ t('建议检查后端服务健康状态。需要我运行诊断命令吗？', 'Recommend checking backend service health. Want me to run a diagnostic?') }}</div>
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
          <span class="stat-num">9</span>
          <span class="stat-label">{{ t('Maven 模块', 'Maven modules') }}</span>
        </div>
        <div class="stat-divider"></div>
        <div class="stat">
          <span class="stat-num">30+</span>
          <span class="stat-label">{{ t('REST 端点', 'REST endpoints') }}</span>
        </div>
        <div class="stat-divider"></div>
        <div class="stat">
          <span class="stat-num">24</span>
          <span class="stat-label">{{ t('内置工具', 'built-in tools') }}</span>
        </div>
        <div class="stat-divider"></div>
        <div class="stat">
          <span class="stat-num">3</span>
          <span class="stat-label">{{ t('记忆层', 'memory layers') }}</span>
        </div>
        <div class="stat-divider"></div>
        <div class="stat">
          <span class="stat-num">∞</span>
          <span class="stat-label">{{ t('并发 Agent', 'concurrent agents') }}</span>
        </div>
      </div>
    </div>

    <!-- ── HOW IT WORKS ── -->
    <section class="section section-dark">
      <div class="section-inner">
        <div class="section-header">
          <span class="section-label">{{ t('运行原理', 'HOW IT WORKS') }}</span>
          <h2 class="section-h2">{{ t('Runtime 跑一个，OS 管一群。', 'Runtime for one. OS for many.') }}</h2>
        </div>

        <div class="arch-diagram">
          <img :src="withBase('/images/architecture.svg')" alt="OryxOS Architecture" class="arch-img"/>
        </div>
      </div>
    </section>

    <!-- ── CORE CAPABILITIES ── -->
    <section class="section section-dark">
      <div class="section-inner">
        <div class="section-header">
          <span class="section-label">{{ t('核心能力', 'CORE CAPABILITIES') }}</span>
          <h2 class="section-h2">{{ t('一个目录，无限 Agent。', 'One directory. Unlimited agents.') }}</h2>
        </div>

        <div class="caps-grid">
          <div v-for="cap in capabilities" :key="cap.num" class="cap-card">
            <div class="cap-top">
              <span class="cap-num">{{ cap.num }}</span>
              <h3 class="cap-title">{{ cap.title }}</h3>
              <p class="cap-desc">{{ cap.desc }}</p>
            </div>
            <pre class="cap-code"><code>{{ cap.code }}</code></pre>
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
          <h2 class="section-h2">{{ t('慢就是快，分阶段克制。', 'Built to grow. Phase by phase.') }}</h2>
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
            <h2 class="cta-h2">{{ t('从一个 Agent 到一群 Agent 的运行底座。', 'From one agent to a fleet.') }}</h2>
            <p class="cta-sub">{{ t('初始化工作区、配置 LLM Provider、开始对话。5 分钟搭起你的第一个 Agent，随时扩展到一群。', 'Initialize the workspace, configure an LLM provider, and start chatting. Your first agent in under 5 minutes — scale to a fleet whenever you\'re ready.') }}</p>
            <div class="cta-btns">
              <a class="btn-dark" :href="t('/zh/docs/what', '/docs/what')">{{ t('查看文档', 'Read the Docs') }}</a>
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
              <pre class="cta-code"><code><span class="code-comment"># 1. {{ t('初始化工作区', 'Initialize the workspace') }}</span>
<span class="code-prompt">❯</span> oryxos init

<span class="code-comment"># 2. {{ t('配置你的 LLM Provider', 'Configure your LLM provider') }}</span>
<span class="code-prompt">❯</span> export DEEPSEEK_API_KEY=your-key-here

<span class="code-comment"># 3. {{ t('启动你的第一个 Agent', 'Start your first agent') }}</span>
<span class="code-prompt">❯</span> oryxos chat --profile ops-agent

<span class="code-comment"># {{ t('或启动 REST API 服务', 'Or launch the REST API') }}</span>
<span class="code-prompt">❯</span> oryxos serve --port 8080</code></pre>
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
          <span class="footer-tagline">{{ t('分布式 Agent Harness OS · 私有部署', 'Distributed Agent Harness OS · Self-hosted') }}</span>
        </div>
        <div class="footer-links">
          <a :href="t('/zh/docs/what', '/docs/what')" class="footer-link">{{ t('文档', 'Docs') }}</a>
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
