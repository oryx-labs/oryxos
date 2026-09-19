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
| teamOwner | string | 团队 id；仅 `workspace-team-acl-enabled` 开且 visibility=WORKSPACE 时裁决；缺省不另拒 |
| orgOwner | string | 组织 id；仅 `workspace-org-acl-enabled` 开且 visibility=WORKSPACE 时裁决；与 `teams.org_id` 对齐；缺省不另拒；`workspace-org-acl-ancestor-enabled` 开时还可匹配祖先（#568） |

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

## asset_governance_revisions (V13 / #537 / #541)

Append-only full-text snapshots when `version-history-enabled`:

- id, resource_type, resource_id, version_label, snapshot_text, actor, created_at
- GET `.../governance/revisions` lists newest-first
- POST `.../governance/revisions/{id}/restore` writes snapshot back to live GOVERNANCE / channels.yaml and appends a new revision
- GET `.../governance/revisions/{id}/diff?against={otherId}` returns unified-diff text of the two snapshots
- Admin UI (#550): list / pick-two diff / restore on Agent·Skill·Knowledge·Channel governance panels

## Runtime wiring

- `AssetAwareAuthorizationServiceImpl` wraps role-based decide when `rbac.enabled && asset-governance.enabled`; optional `OrgParentLookup` when `workspace-org-acl-ancestor-enabled`
- Channel writes: extra `decide(MANAGE_CHANNELS, channel(name))` so the decorator can see the named block. Filter still uses `channel(null)`.


## organizations + teams.org_id (V15 / #554) + parent_org_id (V16 / #566)

Optional org catalog metadata (not used by `AuthorizationService.decide`):

- `organizations(org_id, display_name, created_at, updated_at, parent_org_id?)`
- `organizations.parent_org_id` nullable self-FK (PG `ON DELETE SET NULL`; SQLite clears children on org delete in service). No cycle detection beyond decide depth bound. `decide` / `orgOwner` exact-match by default; optional ancestor match behind `workspace-org-acl-ancestor-enabled` (#568) via `OrgParentLookup`.
- `teams.org_id` nullable FK (PG `ON DELETE SET NULL`; SQLite clears on org delete in service)

CLI: `oryxos org create|list|rename|delete|set-parent`, `oryxos team set-org`.
HTTP under same `oryxos.web.teams-api.enabled` (default off → 404): `/api/v1/orgs`, `PUT /api/v1/teams/{teamId}/org`, `PUT /api/v1/orgs/{orgId}/parent`.
Admin org UI (#556/#570): same teams Admin page — org catalog CRUD + parentOrgId show/set/clear + team set-org/clear; still not used by `AuthorizationService.decide`. No Admin tree UI in this cut.
