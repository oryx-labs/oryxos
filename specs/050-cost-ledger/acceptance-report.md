# Acceptance report: 050-cost-ledger (#476)

**Date**: 2026-09-20  
**Verdict**: Thin cut covers #476 acceptance; routing left to #477.

| Acceptance | Evidence |
|------------|----------|
| 可按任务、团队、模型归因 | `CostLedgerServiceTest.attributionAndPriceVersion`; `CostApiControllerTest.attributionWhenEnabled` |
| 可校验预算、超限阻断或降级 | `CostLedgerServiceTest.overBudgetBlocks` / `overBudgetDegrades`; provider `applyBudgetGate` |
| 账本可与审计成本对账 | `CostLedgerServiceTest.reconcileMatchesAudit` + `/runs/{runId}/reconcile` |
| Default-off | `oryxos.cost.enabled=false`; APIs 404; `flagOffIsNoop` |
