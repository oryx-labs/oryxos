# Why OryxOS

## Enterprises need an Agent Harness OS, not isolated Agents

Every company has work an AI application should be doing right now: the daily report someone compiles by hand, the alert that needs triaging at 3am, the PR digest, the customer question answered for the hundredth time. The models and Agent frameworks are ready. Yet in most companies, applications are still demos.

Why? Four barriers, none of which is the model:

**Defining an agent requires engineers.** Today's mainstream path is code — a framework, a runtime, glue logic, a deployment. The people who best understand the work (ops, support, HR, finance) can describe it perfectly in one sentence, and can't ship an agent at all.

**The data has to leave.** Cloud agent platforms solve infrastructure by taking your data with it. For any company with data-residency, network-perimeter, or audit obligations, that's a non-starter.

**It's a black box.** An agent that can run shell commands, write files, and call external APIs — with no record of what it did, no whitelist on what it *can* do, and no one approving the dangerous steps — is not something an enterprise can put into production.

**One application is easy. A portfolio is not.** A single wrapped LLM loop is a script. Running many business applications — with shared models, Tools, MCP servers, identities, budgets, lifecycle, and governance — is a platform problem, and almost nobody hands you that layer.

## Our answer

OryxOS addresses all four barriers as an open-source, self-hosted Agent Harness OS: one operating foundation to **build, run, govern, and optimize** enterprise AI applications.

![natural language + Memory + Tools + MCP + Skills + Knowledge + Notify = a working Agent](/images/agent-formula-en.svg)

- **Plain language instead of code.** One sentence — or one markdown directory — defines an Agent. The Runtime supplies Memory, built-in Tools, MCP connectors, Skills, notifications, and scheduling. The cost of defining an Agent trends toward zero.
- **Your infrastructure, your data.** One self-hosted binary on your own servers or K8s. No managed service, no telemetry, no cloud lock-in.
- **Glass box, not black box.** Every Tool call and every LLM call is persisted to audit tables from day one — what was invoked, with what arguments, what came back, and how long it took. Sandbox whitelists gate files, shell, and HTTP. Governance is part of the execution path, not a bolt-on.
- **A shared platform for applications.** OryxOS provides common Agent and Task semantics, capability registries, model routing, cost optimization, scheduling, administration, and APIs across enterprise applications. Durable recovery, approval, evaluation, and broader Control Plane features are evolving.

**So every company can build and run its own AI applications — with a shared, governable foundation.**

## Agent teams are an evolving application pattern

Real work is rarely one skill. "Research the market, draft the report, have someone check the numbers, send it to the team" is a *team's* job — decompose, parallelize, review, deliver. OryxOS treats this as an evolving use case built on shared Tasks and governance, not as the definition of the current product:

> **You publish a task in one sentence → OryxOS decomposes it → assembles a team of agents → they collaborate → the result is delivered.**

![How an agent team delivers a task](/images/agent-team-en.svg)

The industry has proven the pattern works (planner–worker–reviewer crews, agent-to-agent protocols). OryxOS is building the runtime semantics needed to make that pattern trustworthy: auditable actions, policy and approval gates, cost attribution, bounded iterations, and recoverable Tasks. See the [Roadmap](./roadmap).

## Why an OS, not another framework

The common assumption is that better agents need better models. In production, the bottleneck is the **harness** — the scaffolding between the model and the real world: the right context assembled before every call, tools that are whitelisted rather than open-ended, execution that is isolated and audited, and messages that reliably reach their destination.

A framework gives you one harness inside your own code. OryxOS ships the harness *as infrastructure* and shares it across applications — which changes who can use it: with a framework, engineers build Agents; with an OS, **anyone who can describe the work** can help deliver an enterprise AI application.

## The foundation OryxOS provides

**Self-hosted execution.** OryxOS runs on your own infrastructure, keeping enterprise data and operations inside your environment.

**A reusable application runtime.** OryxOS gives multiple applications and Agents a shared harness: runtime lifecycle management, common capability registries, and stable interfaces for enterprise integration.

**A controllable execution kernel.** The ReAct loop is self-implemented and inspectable; provider routing is explicit; execution and persistence remain under OryxOS control.

**A platform evolving in public.** The single-node runtime kernel is available today: Agent definitions, model Providers, Memory, Knowledge, Tools, MCP, scheduling, sandbox controls, audit records, REST APIs, and the Web Manager. Durable Task recovery, approval workflows, model routing and cost operations, distributed runtime, and governed multi-Agent teams continue to evolve.

## Why now

**AI programming changed the economics.** A small, focused team can now build and maintain what used to take an engineering organization — and OryxOS itself is built that way, with AI, by contributors who own the result.

**Open standards just matured.** MCP has become the de facto standard for tool interoperability; A2A is emerging for cross-agent coordination. OryxOS builds on both from the start, so what plugs into OryxOS works in the broader ecosystem and vice versa.

**The practical reason is operational consistency.** When each application assembles its own prompt logic, tool dispatch, audit, and cost handling, every production incident becomes application-specific. A shared Runtime with explicit contracts, persistent audit, and a defined extension model lets the enterprise improve these concerns once and reuse the result everywhere.
