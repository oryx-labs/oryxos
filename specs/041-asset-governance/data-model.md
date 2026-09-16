# Data model — 041 asset governance

## GOVERNANCE.yml (sidecar)

Path convention under `oryxos.root`（即 `.oryxos`）:

- `agents/<name>/GOVERNANCE.yml`
- `skills/<name>/GOVERNANCE.yml`
- `knowledge/<name>/GOVERNANCE.yml`

Fields:

| Field | Type | Notes |
| --- | --- | --- |
| owner | string | USER id；缺省则不做 PRIVATE owner 匹配 |
| version | string | 展示/审计用，本刀不做历史表 |
| visibility | PRIVATE / WORKSPACE / PUBLIC | 缺省 = 不加额外拒绝 |
| riskLevel | string | 标签，本刀不驱动裁决 |
| health | ACTIVE / DEPRECATED / OFFLINE | OFFLINE → deny「资产已安全下线」 |

Missing file → empty governance → no extra deny when flag on.

## asset_governance_events (V11)

Append-only audit of governance PUT:

- id, actor, resource_type, resource_id, change_summary, created_at

## Runtime wiring

- `AssetAwareAuthorizationService` wraps role-based decide when `rbac.enabled && asset-governance.enabled`
- Channels: optional governance block later（本刀可跳过写入）
