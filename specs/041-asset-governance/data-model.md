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

## channels.yaml governance block

渠道不写 `GOVERNANCE.yml`。同一字段嵌在 `.oryxos/channels.yaml` 条目下，缺块 = 未设：

```yaml
channels:
  - name: ops-feishu
    type: feishu
    app_id: ${FEISHU_APP_ID}
    app_secret: ${FEISHU_APP_SECRET}
    agent: ops-agent
    governance:
      owner: alice
      visibility: PRIVATE
      health: OFFLINE
```

写入只经 Channel API → `ChannelAdminService` → `ChannelConfigLoader.save`。`resolve()` 不把该块当凭证。未知键不落盘（避免把 appSecret 塞进治理块后被回写）。

## asset_governance_events (V11)

Append-only audit of governance PUT:

- id, actor, resource_type, resource_id, change_summary, created_at

## Runtime wiring

- `AssetAwareAuthorizationServiceImpl` wraps role-based decide when `rbac.enabled && asset-governance.enabled`
- Channel writes: extra `decide(MANAGE_CHANNELS, channel(name))` so the decorator can see the named block. Filter still uses `channel(null)`.
