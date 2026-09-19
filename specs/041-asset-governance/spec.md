# Feature Specification: 资产治理 first cut（Asset Governance）

**Feature Branch**: `feat/041-asset-governance`

**Created**: 2026-09-16

**Status**: First cut + channel yaml block (thin stub — not full 九件套)

**Tracks**: #463（epic #454）

## Intent

给 Agent / Skill / Knowledge 增加可选的 `GOVERNANCE.yml` 侧车元数据（owner、visibility、health 等），并在 `#462` 的唯一决策点 `AuthorizationService.decide` 上叠加一层资产门禁（装饰器），使 OFFLINE 资产不可用、PRIVATE 资产仅 owner/ADMIN 可管。

渠道**不**使用 `GOVERNANCE.yml` 侧车。可选治理字段嵌在 `.oryxos/channels.yaml` 每条渠道的 `governance:` 块（同一 `AssetGovernance`）。缺块 = 未设。写入只经 `ChannelAdminService.add/update/updateGovernance` → `ChannelConfigLoader.save`，不另写侧车以免被覆盖丢掉。写 API 在落盘前额外 `decide(MANAGE_CHANNELS, channel(name))`；Filter 仍映射 `channel(null)`。

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
- `GET/PUT .../governance`（agents / skills / knowledge / channels）
- V11 `asset_governance_events` 审计
- 入站消息 OFFLINE 门禁（`InboundMessageService` + `InboundAssetGovernanceGate`；平台挑战握手仍在适配器层，不经本闸）
- Admin：Agent / Skill / Knowledge 详情「治理」面板（`GET/PUT /api/v1/{agents|skills|knowledge}/{name}/governance`）
- Admin：入站渠道列表 + `channels.yaml` `governance:` 面板（`GET/PUT /api/v1/channels/{name}/governance`）
- 列表过滤：`GET` agents/skills/knowledge/channels 在 rbac+asset-governance 开启时按具名 `decide(READ_WORKSPACE)` 剔除 OFFLINE / PRIVATE 他属主条目
- WORKSPACE 团队门禁：`teamOwner` 字段 + `oryxos.web.asset-governance.workspace-team-acl-enabled`（默认关）；OIDC groups 经 session 缓存注入 `Principal.teamIds`；可选祖先匹配 `workspace-team-acl-ancestor-enabled`（#588，默认关；沿 `parent_team_id` 有界上行；深度复用 `max-org-ancestor-depth`）
- WORKSPACE 组织门禁：`orgOwner` 字段 + `oryxos.web.asset-governance.workspace-org-acl-enabled`（默认关）；`Principal.teamIds` × `teams.org_id` 查找（#558）；可选 session `Principal.orgIds` 缓存（#560，`oryxos.web.rbac.org-ids-from-team-org-enabled`，默认关；有 orgIds 时门禁优先用）；可选 OIDC 专用 claim `oryxos.web.oidc.org-ids-claim`（#590，默认空=关；与 team-derived 可并集）；可选 JIT org catalog `oryxos.web.oidc.jit-org-catalog-enabled`（#592，默认关；须 claim 非空；幂等 ensure）；可选祖先匹配 `workspace-org-acl-ancestor-enabled`（#568，默认关；沿 `parent_org_id` 有界上行；深度 `max-org-ancestor-depth` 默认 16，#579）
- 密码登录可选 `oryxos.web.auth.user-team-ids` → 同 session 缓存（默认空=不声明团队）
- 持久化成员（#535）：V12 `team_memberships` + `oryxos team member-*`；`oryxos.web.rbac.durable-team-memberships-enabled`（默认关）开时与 session 团队取并集
- 团队目录（#539/#581）：V14 `teams(team_id, display_name)` + V17 nullable `parent_team_id` 自引用；`oryxos team create|rename|list|delete|set-org|set-parent`（与成员表解耦，catalog 可选；setParent 有界环检测，深度同 `max-org-ancestor-depth`）
- 组织目录（#554/#566/#573）：V15 `organizations` + nullable `teams.org_id`；V16 nullable `parent_org_id` 自引用；`oryxos org create|list|rename|delete|set-parent` + `team set-org`；HTTP `/api/v1/orgs` + `PUT /api/v1/teams/{id}/org` + `PUT /api/v1/orgs/{id}/parent` + `PUT /api/v1/teams/{id}/parent` 同 `teams-api.enabled`（setParent 有界环检测 #573/#581；decide/orgOwner 默认可精确匹配；#568 可选祖先匹配；深度可配 #579）
- 团队 HTTP API（#546）：`oryxos.web.teams-api.enabled`（默认关→404）；`/api/v1/teams` + `/api/v1/users/{u}/teams`；RBAC 映射 `MANAGE_MEMBERS`
- Admin 管队 UI（#548/#583）：管理台「团队管理」页（list/create/rename/delete + 按用户增删成员 + 客户端按 `parentTeamId` 缩进树）；同 `teams-api.enabled`
- Admin 组织 UI（#556/#570/#575）：同页组织目录 list/create/rename/delete + 展示/设置 `parentOrgId`（`PUT /api/v1/orgs/{id}/parent`）+ 客户端按 `parentOrgId` 缩进树 + 团队 set-org/清 org_id；复用 `/api/v1/orgs` 与 `PUT /api/v1/teams/{id}/org`；同 `teams-api.enabled`
- 版本快照（#537）：V13 `asset_governance_revisions`；`oryxos.web.asset-governance.version-history-enabled`（默认关）开时 PUT 追加全文，GET `.../governance/revisions`
- 版本回滚（#541）：`POST .../governance/revisions/{id}/restore`（agents/skills/knowledge/channels）；同 flag；写回现网并追加新快照
- 版本 diff（#544）：`GET .../governance/revisions/{id}/diff?against={otherId}` 返回统一 diff 文本
- Admin 版本历史 UI（#550）：Agent / Skill / Knowledge / Channel 治理面板下列表、双选 unified-diff、回滚；同 `version-history-enabled`（关→空态）
- `/skills/catalog`：已安装行叠加 GOVERNANCE 列表门禁；012 PUBLIC/PRIVATE 标签仍只管候选过滤，外部未安装行不变
- Agent 作者路径：`validateCatalog` / `generate-files` / `saveFiles(skillBindings)` 经 `isVisible` 谓词过滤可用 Skill（CLI 无谓词时行为不变）
- Knowledge 作者路径：创建 / bind / replace / `saveFiles(knowledgeBindings)` / `generate-files` 经 `isVisible` 过滤（CLI 无谓词时行为不变）

## Out of scope (honest gaps)

- OIDC group→org 绑定仍 deferred；拖拽改父已落地（#595 HTML5 DnD，同种树 + 根区清空；复用 set-parent API / 服务端环检测）。已落地基线：#554 organizations + `teams.org_id`；#566 `parent_org_id`；#581 `parent_team_id` + set-parent；#585 Admin team set-parent UI；#573/#581 setParent 有界环检测；#568 org 祖先匹配有界深度截断；#588 team 祖先匹配有界深度截断；#579 `max-org-ancestor-depth` 可配默认 16；#548/#556/#570/#575/#583/#585 Admin 含 org/team 缩进树与设父；#558 WORKSPACE `orgOwner`；#560 session `orgIds` 缓存 opt-in；相关 API/UI 仍默认关。仍 deferred：Multi-IdP
- OIDC JIT 目录行已落地：`oryxos.web.oidc.jit-team-catalog-enabled`（#552，默认关）；OIDC JIT org catalog：`jit-org-catalog-enabled`（#592，默认关；须 `org-ids-claim` 非空）；OIDC JIT 成员写已落地：`oryxos.web.oidc.jit-team-memberships-enabled`（#562，默认关；无 catalog 行则跳过）；撤销未匹配成员：`revoke-unmatched-team-memberships`（#564，默认关；空 groups → 清空；与 JIT memberships 同路径）
