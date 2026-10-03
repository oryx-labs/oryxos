<script setup>
import { computed } from 'vue'
import { useData, withBase } from 'vitepress'

const { lang } = useData()
const isZh = computed(() => lang.value === 'zh-CN')
const t = (zh, en) => (isZh.value ? zh : en)

const capabilities = computed(() => [
  {
    icon: '🗂️',
    title: t('配置即 Agent', 'Config as Agent'),
    subtitle: t('一份 YAML · 多 Agent 并存 · 零代码', 'One YAML · multi-agent fleet · zero code'),
    code: `# .oryxos/profiles/ops-agent.yaml
name: ops-agent
identity:
  agent_name: 运维小欧
  prompt: 你是一个专业的运维助手...
provider:
  name: deepseek        # 换模型只改这一行
  model: deepseek-chat
  api_key: \${DEEPSEEK_API_KEY}
tools: [shell, read_file, http_get]
settings:
  max_iterations: 10`,
  },
  {
    icon: '💬',
    title: t('CLI 交互', 'CLI Channel'),
    subtitle: t('交互多轮对话 · 会话持久化 · 跨重启恢复', 'Multi-turn chat · sessions persisted · restart-safe'),
    code: `# 初始化工作区
$ oryxos init

# 与 Agent 多轮对话
$ oryxos chat --profile ops-agent
> 帮我检查 nginx 服务状态
⏺ shell → systemctl status nginx
⏺ nginx 正常运行，已持续 12 天

# 会话跨重启恢复
$ oryxos session list`,
  },
  {
    icon: '🌐',
    title: t('REST API', 'REST API'),
    subtitle: t('任意语言接入 · 统一 /api/v1 前缀', 'Any language · unified /api/v1 prefix'),
    code: `# 创建会话
curl -X POST :8080/api/v1/sessions \\
  -d '{"profile":"ops-agent","channel":"web"}'

# 发消息 → 触发 ReAct 循环
curl -X POST :8080/api/v1/sessions/s1/messages \\
  -d '{"content":"检查磁盘使用率"}'

# 读取长期记忆
curl :8080/api/v1/memory`,
  },
])

const scenarios = computed(() => [
  {
    num: '01',
    title: t('运维 Agent', 'DevOps agent'),
    desc: t('shell + 文件工具走命令白名单和路径白名单，自动巡检、分析日志、重启服务，每一步都记录在案。', 'Shell and file tools pass command/path whitelists — automated inspection, log analysis, and restarts, every step on record.'),
  },
  {
    num: '02',
    title: t('跨对话记忆', 'Cross-conversation memory'),
    desc: t('Agent 通过 save_memory / recall_memory 读写 MEMORY.md，记住用户偏好、项目上下文和历史决策。', 'Agents read/write MEMORY.md via save_memory / recall_memory — remembering preferences, project context, and past decisions.'),
  },
  {
    num: '03',
    title: t('零代码技能扩展', 'Zero-code skill'),
    desc: t('写一个 SKILL.md 指令模板 + 复用社区 MCP server，就能让 Agent 生成每日 PR 摘要，不写一行代码。', 'A SKILL.md template plus a community MCP server turns any agent into a daily PR digest bot — no code written.'),
  },
  {
    num: '04',
    title: t('多 Agent 并存', 'Multi-agent fleet'),
    desc: t('一个实例同时运行 ops-agent、review-agent、support-agent，每个 Agent 独立 Profile、独立工具集、独立记忆。', 'One instance runs ops-agent, review-agent, and support-agent side by side — each with its own profile, tools, and memory.'),
  },
  {
    num: '05',
    title: t('审计与合规', 'Audit & compliance'),
    desc: t('每次工具调用、每次 LLM 调用都写入 SQLite 审计表，可回溯、可追责，满足企业内控要求。', 'Every tool call and LLM call lands in SQLite audit tables — traceable and accountable for enterprise compliance.'),
  },
  {
    num: '06',
    title: t('私有化部署', 'Private deployment'),
    desc: t('装在自己的 K8s 或服务器上，数据不出企业，凭证只经环境变量注入，不锁任何云生态。', 'Runs on your own K8s or servers. Data never leaves; credentials come from env vars; no cloud lock-in.'),
  },
  {
    num: '07',
    title: t('多 Provider 路由', 'Multi-provider routing'),
    desc: t('DeepSeek、Qwen、Kimi、Ollama 显式映射并存，按 Profile 指定，切换模型零代码改动。', 'DeepSeek, Qwen, Kimi, and Ollama coexist behind an explicit routing map — switch models per profile, zero code change.'),
  },
  {
    num: '08',
    title: t('统一多渠道', 'Unified channels'),
    desc: t('CLI 和 REST API 两个渠道接入同一个引擎，同一个 Agent 在哪边对话都是同一份记忆和会话。', 'CLI and REST both feed the same engine — one agent, one memory, one session history, whichever channel you use.'),
  },
])

const endpoints = computed(() => [
  {
    label: t('会话 Sessions', 'Sessions'),
    rows: [
      { path: 'POST /sessions', desc: t('创建会话', 'Create a session') },
      { path: 'POST /sessions/{id}/messages', desc: t('发消息，触发 ReAct 循环', 'Send a message; triggers the ReAct loop') },
      { path: 'GET /sessions/{id}', desc: t('查询会话历史', 'Get session history') },
      { path: 'DELETE /sessions/{id}', desc: t('归档会话', 'Archive a session') },
    ],
  },
  {
    label: t('Agent', 'Agents'),
    rows: [
      { path: 'POST /agents/{name}/invoke', desc: t('无状态一次性调用', 'Stateless one-shot invocation') },
      { path: 'GET /profiles', desc: t('列出所有 Agent Profile', 'List all agent profiles') },
    ],
  },
  {
    label: t('运行时 Runtime', 'Runtime'),
    rows: [
      { path: 'GET /memory', desc: t('读取长期记忆 MEMORY.md', 'Read long-term memory (MEMORY.md)') },
      { path: 'GET /tools', desc: t('列出可用工具', 'List available tools') },
      { path: 'GET /health', desc: t('健康检查', 'Health check') },
      { path: 'GET /info', desc: t('运行信息 + Provider 状态', 'Runtime info + provider status') },
    ],
  },
])
</script>

<template>
  <div class="oy-page">
    <!-- ── HERO ── -->
    <section class="oy-hero">
      <div class="oy-hero-inner">
        <div class="oy-badge">
          <span class="oy-badge-dot"></span>
          {{ t('运行时内核 + 企业治理底座', 'Runtime kernel + enterprise governance base') }}
        </div>
        <h1 class="oy-title">OryxOS</h1>
        <p class="oy-title-sub">{{ t('分布式 AI Agent 操作系统', 'The Distributed AI Agent OS') }}</p>
        <p class="oy-hero-desc">
          {{
            t(
              'OryxOS 让一组 AI Agent 像进程一样运行在操作系统上：一份 YAML 定义一个 Agent，统一渠道接入、模型路由、工具调用、记忆与沙箱执行。部署在你自己的基础设施上，数据不出企业。',
              'OryxOS runs a fleet of AI agents like processes on an OS: one YAML defines one agent, with unified channels, model routing, tool calling, memory, and sandboxed execution. Deploy on your own infrastructure — data never leaves.'
            )
          }}
        </p>
        <div class="oy-hero-actions">
          <a class="oy-btn-primary" :href="withBase(t('/zh/docs/what', '/docs/what'))">
            {{ t('开始使用', 'Get Started') }} →
          </a>
          <a class="oy-btn-ghost" :href="withBase(t('/zh/docs/api', '/docs/api'))">
            {{ t('REST API', 'REST API') }}
          </a>
          <a class="oy-btn-ghost" href="https://github.com/xkmeng/oryxos" target="_blank" rel="noopener">GitHub</a>
        </div>
        <div class="oy-hero-note">Java 21 · Spring Boot 3.x · MCP · A2A · SKILL.md</div>
      </div>
    </section>

    <!-- ── PROBLEM ── -->
    <section class="oy-section">
      <div class="oy-section-inner">
        <div class="oy-problem">
          <div class="oy-problem-text">
            <h2 class="oy-section-title">{{ t('企业跑 Agent 的两个核心问题', 'Two core problems of running agents in enterprises') }}</h2>
            <p>{{ t('当 Agent 从演示走向生产，都会撞上同样的两个问题。', 'Every agent that moves from demo to production hits the same two problems.') }}</p>
            <p class="oy-problem-item">
              <strong>{{ t('① 一群 Agent 如何被管起来？', '① How do you manage a fleet of agents?') }}</strong>
              {{ t('不是跑起来一个 Agent，而是让多个 Agent 有统一的接入、记忆、配置和生命周期。', 'Not running one agent — running many with unified channels, memory, config, and lifecycle.') }}
            </p>
            <p class="oy-problem-item">
              <strong>{{ t('② 工具执行如何安全可信？', '② How do you make tool execution safe and accountable?') }}</strong>
              {{ t('Agent 要动文件、执行命令、访问网络——权限怎么控、出事怎么追溯。', 'Agents touch files, run commands, call networks — how do you control permissions and trace incidents?') }}
            </p>
            <p class="oy-solution-line">
              {{ t('OryxOS 专门解决这两个问题，让团队专注在 Agent 的业务逻辑上。', 'OryxOS solves exactly these two problems, so teams can focus on agent business logic.') }}
            </p>
          </div>
          <div class="oy-problem-compare">
            <div class="oy-compare-item oy-compare-bad">
              <div class="oy-compare-label">{{ t('今天的做法', 'Today') }}</div>
              <div class="oy-compare-rows">
                <div class="oy-compare-row"><span class="oy-compare-icon">✗</span><span>{{ t('每个团队基于框架自己搭 Agent 应用，重复造轮子', 'Every team builds agent apps on frameworks, rebuilding the same plumbing') }}</span></div>
                <div class="oy-compare-row"><span class="oy-compare-icon">✗</span><span>{{ t('跑起来就完事，没有审计、没有合规', 'It runs — but no audit trail, no compliance') }}</span></div>
                <div class="oy-compare-row"><span class="oy-compare-icon">✗</span><span>{{ t('Python / Node 技术栈与企业 Java 运维体系脱节', 'Python / Node stacks disconnected from enterprise Java ops') }}</span></div>
                <div class="oy-compare-row"><span class="oy-compare-icon">✗</span><span>{{ t('换模型、加渠道都要改代码', 'Switching models or adding channels means code changes') }}</span></div>
              </div>
            </div>
            <div class="oy-compare-item oy-compare-good">
              <div class="oy-compare-label">OryxOS</div>
              <div class="oy-compare-rows">
                <div class="oy-compare-row"><span class="oy-compare-icon oy-icon-ok">✓</span><span>{{ t('配置即 Agent：一份 YAML，无需写代码', 'Config as agent: one YAML, no code required') }}</span></div>
                <div class="oy-compare-row"><span class="oy-compare-icon oy-icon-ok">✓</span><span>{{ t('每次工具 / LLM 调用都写入审计表', 'Every tool / LLM call persisted to audit tables') }}</span></div>
                <div class="oy-compare-row"><span class="oy-compare-icon oy-icon-ok">✓</span><span>{{ t('Java 21 单 JAR，融入企业现有运维体系', 'Single Java 21 JAR — fits your existing ops toolchain') }}</span></div>
                <div class="oy-compare-row"><span class="oy-compare-icon oy-icon-ok">✓</span><span>{{ t('白名单沙箱 + 环境变量凭证，安全是地基', 'Whitelist sandbox + env-var credentials — security as foundation') }}</span></div>
              </div>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── ARCHITECTURE ── -->
    <section class="oy-section oy-arch-section">
      <div class="oy-section-inner oy-arch-inner">
        <div class="oy-section-header">
          <div class="oy-section-tag">{{ t('架构', 'Architecture') }}</div>
          <h2 class="oy-section-title">{{ t('一个 Spring Boot 进程，运行一组 Agent', 'One Spring Boot process, running a fleet of agents') }}</h2>
        </div>
        <img :src="withBase('/images/architecture.svg')" alt="OryxOS system architecture" class="oy-arch-img" />
        <h3 class="oy-arch-subtitle">ReAct Loop</h3>
        <p class="oy-arch-subdesc">
          {{ t('自实现的推理引擎：组装 Prompt → 调用 LLM → 执行工具 → 回填结果，循环直到给出最终答案。', 'The self-implemented reasoning engine: assemble prompt → call LLM → execute tools → feed results back, looping until a final answer.') }}
        </p>
        <img :src="withBase('/images/react-loop.svg')" alt="OryxOS ReAct loop" class="oy-arch-img oy-arch-img-loop" />
      </div>
    </section>

    <!-- ── CAPABILITIES ── -->
    <section class="oy-section oy-shaded">
      <div class="oy-section-inner oy-wide">
        <div class="oy-section-header">
          <div class="oy-section-tag">{{ t('核心能力', 'Core Capabilities') }}</div>
          <h2 class="oy-section-title">{{ t('配置 · 对话 · 集成', 'Configure · Chat · Integrate') }}</h2>
        </div>
        <div class="oy-cards">
          <div v-for="c in capabilities" :key="c.title" class="oy-card">
            <div class="oy-card-header">
              <span class="oy-card-icon">{{ c.icon }}</span>
              <div>
                <h3 class="oy-card-title">{{ c.title }}</h3>
                <p class="oy-card-subtitle">{{ c.subtitle }}</p>
              </div>
            </div>
            <pre class="oy-code"><code>{{ c.code }}</code></pre>
          </div>
        </div>
      </div>
    </section>

    <!-- ── SCENARIOS ── -->
    <section class="oy-section">
      <div class="oy-section-inner">
        <div class="oy-section-header">
          <div class="oy-section-tag">{{ t('真实场景', 'Real Scenarios') }}</div>
          <h2 class="oy-section-title">{{ t('八个真实使用场景', 'Eight real-world use cases') }}</h2>
        </div>
        <div class="oy-scenarios">
          <div v-for="s in scenarios" :key="s.num" class="oy-scenario">
            <div class="oy-scenario-num">{{ s.num }}</div>
            <div>
              <h3 class="oy-scenario-title">{{ s.title }}</h3>
              <p class="oy-scenario-desc">{{ s.desc }}</p>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── INTEGRATION ── -->
    <section class="oy-section oy-shaded">
      <div class="oy-section-inner">
        <div class="oy-section-header">
          <div class="oy-section-tag">{{ t('接入与扩展', 'Integration & Extension') }}</div>
          <h2 class="oy-section-title">{{ t('三种接入方式，按需选择', 'Three ways in — pick what fits') }}</h2>
        </div>
        <div class="oy-integrations">
          <div class="oy-integration">
            <div class="oy-integration-icon">🖥️</div>
            <h3 class="oy-integration-title">CLI</h3>
            <p class="oy-integration-desc">
              {{
                t(
                  'oryxos 命令行入口：init、chat、serve、profile 管理等 12 个子命令，本地开发与运维的首选方式。',
                  'The oryxos CLI: init, chat, serve, profile management and 12 subcommands — the fastest way for local dev and ops.'
                )
              }}
            </p>
            <div class="oy-installs">
              <code>oryxos init</code>
              <code>oryxos chat --profile ops-agent</code>
              <code>oryxos serve --port 8080</code>
            </div>
          </div>
          <div class="oy-integration oy-integration-featured">
            <div class="oy-integration-icon">🌐</div>
            <h3 class="oy-integration-title">REST API</h3>
            <p class="oy-integration-desc">
              {{
                t(
                  '所有能力暴露在 /api/v1 下。任何能发 HTTP 请求的语言都能接入，Agent 即服务。',
                  'Every capability is exposed under /api/v1. Any language that can send an HTTP request can integrate — agents as a service.'
                )
              }}
            </p>
            <div class="oy-installs">
              <code>POST /api/v1/sessions</code>
              <code>POST /api/v1/sessions/{id}/messages</code>
              <code>POST /api/v1/agents/{name}/invoke</code>
            </div>
            <div class="oy-badges">
              <span class="oy-chip">Sessions</span>
              <span class="oy-chip">Agents</span>
              <span class="oy-chip">Memory</span>
              <span class="oy-chip">Audit</span>
            </div>
          </div>
          <div class="oy-integration">
            <div class="oy-integration-icon">🧩</div>
            <h3 class="oy-integration-title">{{ t('Tool 三档扩展', 'Tool extension tiers') }}</h3>
            <p class="oy-integration-desc">
              {{
                t(
                  '从零代码到深度定制：SKILL.md 指令模板、任意语言的 MCP server、进程内 Java @Tool Bean，门槛自选。',
                  'From zero-code to deep customization: SKILL.md templates, MCP servers in any language, or in-process Java @Tool beans.'
                )
              }}
            </p>
            <div class="oy-badges">
              <span class="oy-chip">SKILL.md</span>
              <span class="oy-chip">MCP</span>
              <span class="oy-chip">@Tool</span>
              <span class="oy-chip">A2A</span>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── API ── -->
    <section class="oy-section">
      <div class="oy-section-inner">
        <div class="oy-section-header">
          <div class="oy-section-tag">{{ t('API 总览', 'API') }}</div>
          <h2 class="oy-section-title">{{ t('完整的 REST API', 'The complete REST API') }}</h2>
          <p class="oy-section-desc">
            {{ t('统一前缀 /api/v1，核心阶段开放 10 个端点，覆盖会话、Agent 调用、记忆与运行时状态。', 'A unified /api/v1 prefix with 10 endpoints covering sessions, agent invocation, memory, and runtime state.') }}
          </p>
        </div>
        <div class="oy-endpoints">
          <div v-for="g in endpoints" :key="g.label" class="oy-endpoint-group">
            <div class="oy-endpoint-group-label">{{ g.label }}</div>
            <div v-for="r in g.rows" :key="r.path" class="oy-endpoint-row">
              <code class="oy-endpoint-path">{{ r.path }}</code>
              <span class="oy-endpoint-desc">{{ r.desc }}</span>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── CTA ── -->
    <section class="oy-section oy-shaded">
      <div class="oy-section-inner">
        <div class="oy-cta">
          <h2 class="oy-cta-title">{{ t('开始构建', 'Start Building') }}</h2>
          <p class="oy-cta-desc">{{ t('五条命令，让你的第一个 Agent 跑起来。', 'Five commands to your first running agent.') }}</p>
          <pre class="oy-code oy-cta-code"><code>git clone https://github.com/xkmeng/oryxos.git && cd oryxos
mvn package -DskipTests

export DEEPSEEK_API_KEY=your-key-here

java -jar oryxos-boot/target/oryxos-boot-*.jar init
java -jar oryxos-boot/target/oryxos-boot-*.jar chat</code></pre>
          <div class="oy-cta-links">
            <a class="oy-btn-primary" :href="withBase(t('/zh/docs/what', '/docs/what'))">{{ t('查看文档', 'Read the Docs') }}</a>
            <a class="oy-btn-ghost" href="https://github.com/xkmeng/oryxos" target="_blank" rel="noopener">GitHub</a>
          </div>
          <p class="oy-cta-note">
            {{
              t(
                'OryxOS 由 oryx-labs 社区打造，长期目标是走进 Apache 基金会，成为 Apache 顶级项目。慢就是快，克制且聚焦。',
                'OryxOS is built by the oryx-labs community, with the long-term goal of joining the Apache Software Foundation as a top-level project. Slow is fast — restrained and focused.'
              )
            }}
          </p>
        </div>
      </div>
    </section>
  </div>
</template>

<style scoped>
.oy-page {
  min-height: 100vh;
  background: #000000;
  color: #f5f5f5;
}

/* ── hero ── */
.oy-hero {
  padding: 100px 24px 80px;
  text-align: center;
  overflow: hidden;
}
.oy-hero-inner {
  max-width: 760px;
  margin: 0 auto;
  display: flex;
  flex-direction: column;
  align-items: center;
}
.oy-badge {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 6px 16px;
  border-radius: 20px;
  border: 1px solid #333333;
  background: #0d0d0d;
  color: #a3a3a3;
  font-size: 12px;
  margin-bottom: 28px;
}
.oy-badge-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #f5f5f5;
  animation: oy-pulse 2s infinite;
}
@keyframes oy-pulse {
  0%, 100% { opacity: 1; transform: scale(1); }
  50% { opacity: 0.4; transform: scale(1.4); }
}
.oy-title {
  margin: 0 0 12px;
  line-height: 1;
  font-size: clamp(72px, 14vw, 120px);
  font-weight: 900;
  letter-spacing: -0.03em;
  color: #f5f5f5;
}
.oy-title-sub {
  font-size: 18px;
  color: #a3a3a3;
  margin: 0 0 20px;
}
.oy-hero-desc {
  font-size: 16px;
  line-height: 1.7;
  color: #a3a3a3;
  max-width: 620px;
  margin: 0 0 32px;
}
.oy-hero-actions {
  display: flex;
  gap: 12px;
  flex-wrap: wrap;
  justify-content: center;
  margin-bottom: 20px;
}
.oy-btn-primary {
  padding: 11px 28px;
  border-radius: 8px;
  background: #f5f5f5;
  color: #000000;
  font-weight: 600;
  font-size: 14px;
  text-decoration: none;
  transition: opacity 0.2s, transform 0.15s;
}
.oy-btn-primary:hover {
  opacity: 0.75;
  transform: translateY(-1px);
}
.oy-btn-ghost {
  padding: 11px 28px;
  border-radius: 8px;
  border: 1px solid #333333;
  color: #d4d4d4;
  font-weight: 600;
  font-size: 14px;
  text-decoration: none;
  transition: border-color 0.2s, background 0.2s;
}
.oy-btn-ghost:hover {
  border-color: #f5f5f5;
  background: #0d0d0d;
}
.oy-hero-note {
  font-size: 12px;
  color: #737373;
}

/* ── sections ── */
.oy-section {
  padding: 72px 24px;
}
.oy-section-inner {
  max-width: 1000px;
  margin: 0 auto;
}
.oy-wide {
  max-width: 1400px;
}
.oy-shaded {
  background: #0d0d0d;
}
.oy-section-header {
  text-align: center;
  margin-bottom: 48px;
}
.oy-section-tag {
  display: inline-block;
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.1em;
  text-transform: uppercase;
  color: #a3a3a3;
  padding: 4px 12px;
  border-radius: 20px;
  border: 1px solid #333333;
  background: #0d0d0d;
  margin-bottom: 14px;
}
.oy-section-title {
  font-size: clamp(22px, 4vw, 32px);
  font-weight: 700;
  color: #f5f5f5;
  margin: 0 0 12px;
}
.oy-section-desc {
  font-size: 15px;
  color: #a3a3a3;
  max-width: 620px;
  margin: 0 auto;
  line-height: 1.6;
}

/* ── problem / compare ── */
.oy-problem {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 48px;
  align-items: start;
}
.oy-problem-text p {
  color: #a3a3a3;
  line-height: 1.7;
  margin: 0 0 14px;
  font-size: 15px;
}
.oy-problem-item strong {
  color: #f5f5f5;
  display: block;
  margin-bottom: 4px;
}
.oy-solution-line {
  color: #f5f5f5 !important;
  font-weight: 600;
}
.oy-problem-compare {
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.oy-compare-item {
  padding: 20px;
  border-radius: 12px;
  border: 1px solid #262626;
}
.oy-compare-bad {
  background: #0a0a0a;
}
.oy-compare-good {
  background: #0d0d0d;
  border-color: #d4d4d4;
}
.oy-compare-label {
  font-size: 11px;
  font-weight: 700;
  color: #737373;
  margin-bottom: 12px;
  text-transform: uppercase;
  letter-spacing: 0.08em;
}
.oy-compare-rows {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.oy-compare-row {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  font-size: 13px;
  color: #a3a3a3;
  line-height: 1.5;
}
.oy-compare-icon {
  flex-shrink: 0;
  font-style: normal;
  color: #bbbbbb;
  font-weight: 700;
  width: 14px;
}
.oy-icon-ok {
  color: #f5f5f5;
}

/* ── architecture diagrams ── */
.oy-arch-section {
  padding: 72px 24px;
}
.oy-arch-inner {
  max-width: 1100px;
}
.oy-arch-img {
  width: 100%;
  display: block;
  border: 1px solid #262626;
  border-radius: 12px;
}
.oy-arch-subtitle {
  text-align: center;
  font-size: 20px;
  font-weight: 700;
  color: #f5f5f5;
  margin: 40px 0 8px;
}
.oy-arch-subdesc {
  text-align: center;
  font-size: 14px;
  color: #a3a3a3;
  max-width: 620px;
  margin: 0 auto 24px;
  line-height: 1.6;
}
.oy-arch-img-loop {
  max-width: 860px;
  margin: 0 auto;
}

/* ── capability cards ── */
.oy-cards {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  grid-auto-rows: 1fr;
  gap: 16px;
}
.oy-card {
  padding: 20px;
  border-radius: 14px;
  border: 1px solid #262626;
  background: #000000;
  display: flex;
  flex-direction: column;
  gap: 12px;
  transition: border-color 0.2s, box-shadow 0.2s;
  min-width: 0;
  overflow: hidden;
}
.oy-card:hover {
  border-color: #f5f5f5;
  box-shadow: 0 4px 16px rgba(255, 255, 255, 0.06);
}
.oy-card-header {
  display: flex;
  align-items: flex-start;
  gap: 12px;
}
.oy-card-icon {
  font-size: 28px;
  flex-shrink: 0;
}
.oy-card-title {
  font-size: 17px;
  font-weight: 700;
  color: #f5f5f5;
  margin: 0 0 2px;
}
.oy-card-subtitle {
  font-size: 12px;
  color: #737373;
  margin: 0;
}
.oy-code {
  background: #0d0d0d;
  border: 1px solid #262626;
  border-radius: 8px;
  padding: 14px 16px;
  font-size: 12px;
  line-height: 1.6;
  color: #d4d4d4;
  overflow-x: auto;
  margin: 0;
  white-space: pre;
}
.oy-code code {
  font-family: 'JetBrains Mono', 'Fira Code', ui-monospace, monospace;
  background: none;
  color: inherit;
}

/* ── scenarios ── */
.oy-scenarios {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 20px;
}
.oy-scenario {
  display: flex;
  gap: 16px;
  padding: 20px;
  border-radius: 12px;
  border: 1px solid #262626;
  background: #0a0a0a;
}
.oy-scenario-num {
  font-size: 28px;
  font-weight: 900;
  color: #262626;
  line-height: 1;
  flex-shrink: 0;
  font-variant-numeric: tabular-nums;
}
.oy-scenario-title {
  font-size: 15px;
  font-weight: 600;
  color: #f5f5f5;
  margin: 0 0 6px;
}
.oy-scenario-desc {
  font-size: 13px;
  color: #a3a3a3;
  line-height: 1.65;
  margin: 0;
}

/* ── integration cards ── */
.oy-integrations {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 20px;
}
.oy-integration {
  background: #000000;
  border: 1px solid #262626;
  border-radius: 16px;
  padding: 28px 24px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.oy-integration-featured {
  border-color: #f5f5f5;
}
.oy-integration-icon {
  font-size: 28px;
}
.oy-integration-title {
  font-size: 17px;
  font-weight: 700;
  color: #f5f5f5;
  margin: 0;
}
.oy-integration-desc {
  font-size: 14px;
  color: #a3a3a3;
  line-height: 1.6;
  margin: 0;
  flex: 1;
}
.oy-installs {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.oy-installs code {
  font-family: 'JetBrains Mono', 'Fira Code', ui-monospace, monospace;
  font-size: 12px;
  background: #0d0d0d;
  border: 1px solid #262626;
  border-radius: 6px;
  padding: 5px 10px;
  color: #f5f5f5;
  display: block;
  overflow-x: auto;
  white-space: nowrap;
}
.oy-badges {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.oy-chip {
  padding: 3px 10px;
  border-radius: 12px;
  font-size: 11px;
  font-weight: 700;
  background: #f0f0f0;
  border: 1px solid #333333;
  color: #d4d4d4;
}

/* ── endpoint grid ── */
.oy-endpoints {
  display: flex;
  flex-direction: column;
  gap: 28px;
}
.oy-endpoint-group {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.oy-endpoint-group-label {
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.1em;
  text-transform: uppercase;
  color: #a3a3a3;
  margin-bottom: 4px;
}
.oy-endpoint-row {
  display: flex;
  align-items: baseline;
  gap: 16px;
  padding: 8px 14px;
  border-radius: 8px;
  background: #0a0a0a;
  border: 1px solid #262626;
  flex-wrap: wrap;
}
.oy-endpoint-path {
  font-family: 'JetBrains Mono', 'Fira Code', ui-monospace, monospace;
  font-size: 12px;
  color: #f5f5f5;
  background: #f0f0f0;
  border: 1px solid #333333;
  padding: 2px 8px;
  border-radius: 4px;
  flex-shrink: 0;
  white-space: nowrap;
}
.oy-endpoint-desc {
  font-size: 13px;
  color: #a3a3a3;
  flex: 1;
}

/* ── CTA ── */
.oy-cta {
  text-align: center;
  max-width: 680px;
  margin: 0 auto;
}
.oy-cta-title {
  font-size: 28px;
  font-weight: 700;
  color: #f5f5f5;
  margin: 0 0 12px;
}
.oy-cta-desc {
  font-size: 15px;
  color: #a3a3a3;
  margin: 0 0 24px;
}
.oy-cta-code {
  text-align: left;
  margin-bottom: 28px;
}
.oy-cta-links {
  display: flex;
  gap: 12px;
  justify-content: center;
  flex-wrap: wrap;
}
.oy-cta-note {
  margin: 20px auto 0;
  font-size: 12px;
  color: #737373;
  max-width: 560px;
  line-height: 1.7;
}

/* ── responsive ── */
@media (max-width: 900px) {
  .oy-integrations {
    grid-template-columns: 1fr;
  }
}
@media (max-width: 768px) {
  .oy-hero {
    padding: 72px 20px 60px;
  }
  .oy-problem {
    grid-template-columns: 1fr;
  }
  .oy-cards {
    grid-template-columns: 1fr;
  }
  .oy-scenarios {
    grid-template-columns: 1fr;
  }
  .oy-section {
    padding: 48px 20px;
  }
}
</style>
