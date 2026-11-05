# OryxOS 是什么

**OryxOS 是面向企业 AI 应用的开源、自托管 Agent Harness OS。** 它是一套运行底座，让企业把 Agent 放进受控的上下文、环境、工具、权限与治理体系中运行。

![OryxOS 整体架构](/images/oryxos-overview-zh.svg)

企业可以在 OryxOS 上构建研发自动化、知识支持、工单协同、运维诊断等专属 AI 应用。应用负责自己的业务规则、数据、系统集成与用户体验；OryxOS 提供其下方共享的 Runtime。

## 企业需要的是 Harness，不是某一个 Agent

模型与 Agent 会持续变化。Codex、Claude Code、Gemini 和企业自研 Agent 都应是可以替换的执行者。企业真正需要沉淀的，是这些执行者工作的环境：

- 可以使用的业务上下文与知识；
- 可以操作的系统、Tool 与权限；
- 必须遵守的策略、审批与沙箱边界；
- 判断任务完成的验收标准与执行证据；
- 用于改进下一次执行的 Trace、成本、评测与反馈。

这就是 **Agent Harness**。OryxOS 将它做成跨应用复用的基础设施，而不是让每个应用各自重建一遍。

## 每一次行动都走同一条执行路径

应用可以组合需要的 Agent、模型、Knowledge、Tool、MCP Server 和 Flow；但每一次有实际影响的行动都要经过统一的治理路径：

```text
Task → 上下文 / 执行环境 → 身份 / 授权 → 策略 / 审批
     → 模型 / Tool 执行 → 交付物 / 证据 → 审计 / Trace / 成本 → 状态更新
```

应用可以自由组合能力，但不能绕过治理。治理属于执行本身，不是一份事后的报告。

## OS 提供什么

| 层 | 负责什么 |
| --- | --- |
| **Task Runtime** | 生命周期、状态、会话、交付物、持久任务、恢复与人工交互。 |
| **上下文与执行环境** | 知识、记忆、企业系统连接与受控执行环境。 |
| **能力层** | 模型路由、Tool、MCP、Skill 与可扩展的企业集成。 |
| **治理层** | 身份、授权、策略、审批、沙箱边界与审计。 |
| **运行层** | Trace、证据、成本归集、评测、可靠性与反馈。 |

## 当前进展

当前已提供单节点 Runtime：Agent 定义、模型 Provider、Memory、Knowledge、内置 Tool、MCP、Skill、调度、沙箱控制、审计记录、REST API 和 Web Manager。

持久 Task、Checkpoint 与恢复、审批、策略分发、模型路由与成本运营、评测和分布式执行仍在持续演进，见[路线图](./roadmap)。

## 为什么叫 OS

模型生成文本；Harness 给 Agent 加上上下文、工具、记忆、执行机制与边界；**Agent Harness OS** 则为多个企业应用提供同一套 Runtime 契约与治理路径。

OryxOS 有意保持通用。研发 Harness 可以在其上接入代码仓库、原生 Code Agent、CI、浏览器和真机测试；客服 Harness 可以接入 CRM、工单与人工接管。它们都是构建在 OryxOS 上的应用，OS 提供共同的执行与控制能力。
