# Feature Specification: 资产治理 first cut（Asset Governance）

**Feature Branch**: `feat/041-asset-governance`

**Created**: 2026-09-16

**Status**: First cut + channel yaml block (thin stub — not full 九件套)

**Tracks**: #463（epic #454）

## Intent

给 Agent / Skill / Knowledge 增加可选的 `GOVERNANCE.yml` 侧车元数据（owner、visibility、health 等），并在 `#462` 的唯一决策点 `AuthorizationService.decide` 上叠加一层资产门禁（装饰器），使 OFFLINE 资产不可用、PRIVATE 资产仅 owner/ADMIN 可管。

渠道**不**使用 `GOVERNANCE.yml` 侧车。可选治理字段嵌在 `.oryxos/channels.yaml` 每条渠道的 `governance:` 块（同一 `AssetGovernance`）。缺块 = 未设。写入只经 `ChannelAdminService.add/update` → `ChannelConfigLoader.save`，不另写侧车以免被覆盖丢掉。写 API 在落盘前额外 `decide(MANAGE_CHANNELS, channel(name))`；Filter 仍映射 `channel(null)`。

## Hard constraints

- `oryxos.web.asset-governance.enabled` 默认 **false** — 关闭时零行为变化
- 资产门禁**只**在 `rbac.enabled && asset-governance.enabled` 时生效；仍只走 `AuthorizationService.decide`，不引入第二套权限路径、不引入 Spring Security filter chain
- 缺 `GOVERNANCE.yml` 或缺渠道 `governance:` 块 = 未设治理元数据 → **不加额外拒绝**（兼容存量资产）
- API_KEY 主体：本刀仅受 OFFLINE 约束（不做 owner 匹配）
- 治理块不含凭证；`resolve()` 不把 governance 当凭证做 `${ENV}` 替换

## In scope

- `GOVERNANCE.yml` sidecar + `AssetGovernanceStore`
- 渠道 `channels.yaml` 的 `governance:` 块（round-trip）；`AssetGovernanceStore.load(channel, name)` 读同一文件
- `AssetAwareAuthorizationServiceImpl` 装饰 `RoleBasedAuthorizationServiceImpl`（渠道 OFFLINE/PRIVATE 复用，不另写裁决）
- 绑定 / 调用额外 `decide`（Skill / Knowledge / Agent OFFLINE）
- 渠道增/改/删额外 `decide(MANAGE_CHANNELS, channel(name))`
- `GET/PUT .../governance`（agents / skills / knowledge）
- V11 `asset_governance_events` 审计

## Out of scope (honest gaps)

- 渠道治理管理台 UI
- 入站 webhook 按治理门禁
- Team ACL / JIT teams / 组织归属
- 完整版本历史
- Catalog 可见性仍只是标签，不驱动列表过滤
