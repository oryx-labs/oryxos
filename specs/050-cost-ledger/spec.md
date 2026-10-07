# Feature Specification: Task-level cost ledger, budget & quotas (#476)

**Feature Branch**: `feat/476-task-cost-ledger-budget`

**Created**: 2026-09-20

**Status**: First cut (thin)

**Tracks**: #476 (epic #459); sibling #477 owns explainable model routing

## Intent

On top of Day-One `llm_calls` write-time cost (016), add a **task/run-level cost ledger** that
attributes tokens, price version, tool cost and latency by task, Agent, team and model; enforce
**budgets/quotas** with block or cheap-model degrade when enabled.

## Hard constraints

- `oryxos.cost.enabled` default **false** (zero runtime behavior change)
- `oryxos.cost.enforcement-enabled` default **false** (ledger can be on without blocking)
- Do **not** implement explainable routing / sensitivity / difficulty policies (#477)
- Reuse `llm_pricing` write-time micros; stamp `price_version` onto ledger rows

## Acceptance mapping (#476)

| Acceptance | Coverage |
|------------|----------|
| 可按任务、团队、模型归因 | `GET /api/v1/cost/attribution` + `CostLedgerService.query` includes `priceVersions` |
| 可校验预算、超限阻断或降级 | `checkBudget` + provider `applyBudgetGate` BLOCK / DEGRADE |
| 账本可与审计成本对账 | `GET /api/v1/cost/runs/{runId}/reconcile` vs `llm_calls.cost_micros` |

## Out of scope

- Explainable model routing / fallback reasons (#477)
- Admin UI charts beyond API
- Multi-currency; predicted cost before call
