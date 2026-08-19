# Specification Quality Checklist: 知识库（Knowledge Base）

**Purpose**: Validate specification completeness and quality before proceeding to planning
**Created**: 2026-08-17
**Feature**: [spec.md](../spec.md)

## Content Quality

- [x] No implementation details (languages, frameworks, APIs)
- [x] Focused on user value and business needs
- [x] Written for non-technical stakeholders
- [x] All mandatory sections completed

## Requirement Completeness

- [x] No [NEEDS CLARIFICATION] markers remain
- [x] Requirements are testable and unambiguous
- [x] Success criteria are measurable
- [x] Success criteria are technology-agnostic (no implementation details)
- [x] All acceptance scenarios are defined
- [x] Edge cases are identified
- [x] Scope is clearly bounded
- [x] Dependencies and assumptions identified

## Feature Readiness

- [x] All functional requirements have clear acceptance criteria
- [x] User scenarios cover primary flows
- [x] Feature meets measurable outcomes defined in Success Criteria
- [x] No implementation details leak into specification

## Notes

- 2026-08-19 PR #195 评审修订：① 配置键统一——向量索引存储 `knowledge.store`（默认 `sqlite`，D1），后端插件选择只走库级清单 `backend:`（默认 `local`），废除歧义的全局 `knowledge.backend`；② embedding 未配置语义从「回退注册表首个可用项」改为「显式不可用」（导入可读报错 + 检索关键词降级），规避静默取到无 embedding 端点的 provider；③ 该 PR 不再改动 `.specify/feature.json`（012 工作流仍在使用）。FR/US/SC 编号不变。
- 说明：FR-002/FR-005 提及「软连接」「prompt 注入」等机制词，是宪法原则 IV（Skill 绑定范式同构）的领域语言而非实现选型，属于本项目 spec 的既有惯例（参照 012-agent-skill-links）。
- 「向量化 / embedding / 混合检索」为能力描述（检索质量契约），具体库/算法选型留 plan 阶段；调研结论已备案于 research 备忘（见 plan 阶段输入）。
- 所有决策点均以合理默认值落入 Assumptions，无待澄清项；plan 阶段需停点确认的宪法项：新建模块声明、契约上移 core、新表与新配置键。
- 2026-08-17 修订：按维护者要求对标 AgentScope-Java RAG 抽象，将「知识标准操作」上升为底座级插件契约（FR-006 拆分必选检索/可选管理 + 能力声明、FR-015 远程后端挂载、FR-009 能力感知渲染、新增 KnowledgeBackend 实体与两条边界场景）；重跑全部检查项仍通过。对标决策备案于 research.md D9。
- 2026-08-18 第六轮（面向干系人重组）：以「拿去对齐需求的人类可读文档」标准重审并重写呈现层——新增背景与目标（为什么做/一句话/三个画面）、术语表（13 词条 + MUST 约定）、显式范围表（交付/不做+去向）、关键决策记录表（12 项拍板集中呈现，与假设分离）、依赖与风险表（5 项）、修订历史表；Edge Cases 按类分组；内部引用自足化。**需求语义、FR/US/SC 编号、speckit 标准节头全部保持不变**（traceability.md 与 research.md 引用链未受影响）。重跑全部检查项通过。
- 2026-08-18 第五轮（clarify + 追踪矩阵）：/speckit-clarify 3 问 3 答（双缓冲重建→FR-024、聚合全局 top-K→FR-020、两段式上传→FR-008/实体状态机），决策记录于 spec `## Clarifications`；新建 traceability.md（FR↔US↔SC 三向矩阵）——揪出并修复：US5 缺专属 SC（补 SC-011）、US6/US7 文件顺序颠倒、SC-008 编号错位；唯一孤儿 FR-016 判定为合法架构需求（溯源 D10 拍板）。全部检查项复验通过。
- 2026-08-18 第四轮修订（brainstorming 收敛维护者主流程想法）：维护者 8 要素与 spec 对照，7 项已覆盖，唯一新需求域为效果评估体系。新增 US7（管理员监控使用效果，P2）、FR 组 F（FR-022 前瞻埋点 / FR-023 运营看板）、SC-009/010；FR-003 文件类型扩至文本型 PDF（含扫描件拒绝与页码出处）；Assumptions 补两条拍板边界。决策依据备案 research.md D11。重跑全部检查项通过。
- 2026-08-18 第三轮修订：按「角色 × 界面 × 完整旅程」重组全文——新增角色/界面定义表（4 角色 5 界面）；用户故事重排为 6 条完整旅程（终端用户 P1 / 管理台全生命周期 P1 / Agent 创建三路径关联 P2 / GitOps+CLI P2 / 远程挂载 P3 / 无 key 自测 P3）；补六个用户视角缺口：FR-018（创建/编辑/一句话生成的关联入口）、FR-019（绑定为管理面动作，运行时不可自改）、FR-020（多库聚合检索 + 单库限定参数）、FR-021（`oryxos knowledge list` CLI）、SC-007（三路径绑定一致性）、SC-008（一句话生成绑定建议准确率）；FR 按 A~E 五组重编排（实体生命周期/关联/使用/契约插件/管理观测）。重跑全部检查项通过。
- 2026-08-18 修订：检索质量需求经维护者讨论拍板三项——① FR-004 检索流水线标准分段（双路召回 + 名次融合 + 精排槽位，v1 不实现精排）；② FR-016 检索基建为底座通用组件（记忆/知识分层统一）；③ FR-017 本地知识库全文跟读为硬需求（自动入 read_file 白名单）。新增验收场景 1a；Assumptions 补精排边界与分层统一决策记录。重跑全部检查项仍通过。决策依据备案于 research.md D10。
