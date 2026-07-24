# Governance Amendment / 治理修订

> PR 必须醒目引用本节。宪章已在独立治理流程中修订为 v2.0.0；本 Feature 不再次修改宪章，而是验证实现严格落在既有例外内。

## 适用条款

- Principle IV：一个目录等于一个 Agent；公共 Skill 市场是禁止跨 Agent 共享能力库的唯一受控例外。
- Principle VIII：`AGENT.md` 定义 Agent 运行配置，但 Skill 安装关系必须由 Agent 目录中的标准软链接表达。

## 本 Feature 使用例外的理由

公共 Skill 包需要像本地市场一样只保存一份，并由管理员显式安装给一个或多个 Agent。复制包会造成版本、禁用和审计语义分叉；把名单写入 YAML 或数据库又会形成第二真相源。标准相对软链接同时提供可见、可移动、可扫描的安装证据。

## 不可突破的边界

1. 公共内容根只有 `.oryxos/skills/<skill>/SKILL.md`；不得增加第二共享能力根。
2. 关联只认 `.oryxos/agents/<agent>/skills/<skill> -> ../../../skills/<skill>`；不得用 `AGENT.md`、`AGENTS.md`、数据库、缓存或独立索引表达关联真相。
3. 运行时只把有效、enabled 关联的 name/description/entry 放入 L1；L2/L3 必须由既有 Tool 显式读取并继续经过权限、沙箱和审计。
4. 不新增 `use_skill` Tool，不把 Skill 注册进 `ToolRegistry`，`allowed-tools`、activation 和 requires 都不授予或扩大权限。
5. 删除检查本期扫描全部 Agent；不以性能优化为由引入反向状态源。

## 兼容与安全影响

- 旧 `AGENT.md skills:` 继续可解析但只告警、无运行时作用，不自动迁移。
- 旧 `skills/*.md` 与 Agent 目录内真实 Skill 子目录保持 legacy/unmanaged，不被公共 API 改写。
- 导入是管理员的显式信任动作；结构校验只限制文件系统与资源风险，不证明指令或脚本善意。
- Agent 创建所选 Skill 必须生成真实标准链接，且不再生成 `example` Skill。
- 普通删除遇到关联返回 typed 409；只有前端再次明确确认才调用 force。force 执行时重新扫描，不信任旧列表。
- 强删不写 operation journal、不增加启动恢复任务；归档失败时仅在同一进程内尽力重建本次已解除且仍为空的标准链接。

## PR 评审结论模板

```text
Governance Amendment / 治理修订

本 PR 落实宪章 v2.0.0 Principle IV/VIII 已批准的公共 Skill 市场例外：
- 唯一公共根：.oryxos/skills
- 唯一关联：Agent 目录内 ../../../skills/<skill> 标准相对软链接
- 无 AGENT.md/AGENTS.md/数据库关联真相
- 无 use_skill、ToolRegistry 注册或 Tool 权限扩张

兼容性：旧 skills 声明/文件保持可解析或 unmanaged，不自动迁移。
安全性：L2/L3 仍经过显式 Tool、snapshot、路径边界、沙箱与审计。
```

## 最终实现与验收证据（2026-07-24）

- 唯一生产装配链为 `PublicSkillCatalog`、`SkillAssociationService`、`SkillGraphCoordinator`、`PublicSkillManagementService` 与 `SkillResourceAccessGuard`；公共根固定为 `.oryxos/skills`。
- Agent create/save 在暂存 Agent 目录中建立全部 `../../../skills/<skill>` 链接后原子发布；详情从实际文件系统关联派生，AGENT.md 不保存名单且不创建 example。
- ContextLoader 只渲染固定请求 snapshot 的 name/description/entry；ToolExecutor 在既有 Tool、沙箱和审计链路中复验 L2/L3。
- 普通删除重新扫描所有 Agent 并以 `SKILL_IN_USE` typed 409 零副作用返回；force 在同一锁域重扫、排序加锁、解除标准链接并归档，失败只做同进程尽力补偿。
- 工作区初始化和生产源码均不创建 `.operations/skills`，不存在持久化 force-delete journal、启动恢复类型或 ready 阻塞逻辑。

最终命令：

```text
mvn -DargLine='-javaagent:<byte-buddy-agent> -XX:+EnableDynamicAgentLoading' clean verify
mvn -pl oryxos-boot -am -Dtest=SkillGlobalStateRestartIT -Dgroups=integration -DexcludedGroups= test
cd oryxos-web/src/main/frontend && npm test -- --run
cd oryxos-web/src/main/frontend && npm run build
git diff --check
```

结果：Maven 596 tests / 0 failures / 0 errors；重启 integration test 通过；前端 14 tests 与生产构建通过。
