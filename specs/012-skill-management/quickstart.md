# Quickstart: 验收公共 Skill、软链接关联与渐进加载

示例使用 `ops-agent`、`support-agent` 和 `weather`。Skill 是可执行指令级内容，只导入已审查的测试包。

## 1. 前置与启动

- JDK 21：`java -version`
- 两个 Agent 已存在，且需要加载 L2 时在各自 `AGENT.md` 显式授权 `read_file`
- 工作区文件系统支持软链接与同 FileStore 原子移动

```bash
mvn clean verify
java -jar oryxos-boot/target/oryxos-boot-0.1.1-RELEASE.jar serve --port 8080
```

前端开发模式：

```bash
cd oryxos-web/src/main/frontend
npm install
npm run dev
```

生产验收使用 `http://127.0.0.1:8080/admin/`。

## 2. 制作单 Skill ZIP

创建 `weather/SKILL.md`：

```markdown
---
name: weather
description: 查询天气并给出穿衣建议；用户询问天气、温度或出行时使用。
version: 1.0.0
license: Apache-2.0
metadata:
  author: local-test
---

# Weather

确有需要时读取 `references/rules.md`。
```

创建 `weather/references/rules.md`，包含唯一标记 `MATCHED_WEATHER_RULES_012`，然后：

```bash
zip -r weather.zip weather
```

另做一个正文和资源带不同唯一标记的合法 Skill，用于证明未命中正文不会预载。

## 3. 导入公共包

```bash
curl -sS -X POST \
  -F 'file=@weather.zip;type=application/zip' \
  http://127.0.0.1:8080/api/v1/skills

curl -sS http://127.0.0.1:8080/api/v1/skills
curl -sS http://127.0.0.1:8080/api/v1/skills/weather
```

预期：公共目录 `.oryxos/skills/weather/` 原子出现，状态 enabled、来源 upload；API 只返回相对路径与资源统计，不返回正文或工作区绝对路径；`.oryxos/.staging/skill-import/` 无失败残留。

## 4. 建立两个 Agent 关联

```bash
curl -sS -X PUT \
  http://127.0.0.1:8080/api/v1/agents/ops-agent/skills/weather
curl -sS -X PUT \
  http://127.0.0.1:8080/api/v1/agents/support-agent/skills/weather

readlink .oryxos/agents/ops-agent/skills/weather
readlink .oryxos/agents/support-agent/skills/weather
```

两个 `readlink` 都必须逐字输出：

```text
../../../skills/weather
```

再查询：

```bash
curl -sS http://127.0.0.1:8080/api/v1/agents/ops-agent/skills
curl -sS http://127.0.0.1:8080/api/v1/skills/weather
```

预期 Agent 列表来自实际链接，公共详情的 `linkedAgents` 含两个 Agent。`AGENT.md` 不出现或改写 `skills:`，Agent 下不生成 `example` 目录。

将整个 `.oryxos` 所在工作区移动到另一个测试路径后重复 `readlink` 和一次请求，标准相对关联仍然有效。

## 5. US1 渐进加载核心验收

用可记录 request/tool call 的 mock provider 发起只命中 weather 的请求：

1. 第一个 system prompt 包含已关联 enabled Skill 的 `name/description/entry`。
2. 第一个 prompt 中所有 Skill 的正文标记与 resource 标记出现次数均为 0。
3. 模型命中 weather 后只对 Agent 内 entry 发出 `read_file`，系统复验一层标准链接后读取公共 `SKILL.md`。
4. 只有正文步骤确实需要时，才读取 `references/rules.md`。
5. `MATCHED_WEATHER_RULES_012` 最终出现，未命中 Skill 的唯一标记从未出现。
6. `tool_invocations` 记录 L2/L3 的既有 `read_file`；不存在 `use_skill` Tool 或自动工具执行旁路。

移除 Agent 的 `read_file` 权限后重跑：L1 可以列出元数据，但加载明确失败且不能自动扩权。只在 `AGENT.md skills:` 写入一个未关联名称，重跑后该名称不得进入 L1。

## 6. 全局禁用、启用与单 Agent 解除

```bash
curl -sS -X PUT \
  -H 'Content-Type: application/json' \
  -d '{"enabled":false}' \
  http://127.0.0.1:8080/api/v1/skills/weather
```

预期公共 marker 出现，两个软链接仍存在；两个 Agent 的新请求都不再发现 weather。重启服务仍为 disabled。

```bash
curl -sS -X PUT \
  -H 'Content-Type: application/json' \
  -d '{"enabled":true}' \
  http://127.0.0.1:8080/api/v1/skills/weather

curl -sS -X DELETE \
  http://127.0.0.1:8080/api/v1/agents/support-agent/skills/weather
```

预期重新启用后两个 Agent 的下一请求恢复；解除后仅 support-agent 的标准链接消失，公共包和 ops-agent 不变。旧 Session 历史不被追溯改写；不可发现性用新 Session 验证。

## 7. 删除 A → B 验收

确保 weather 至少仍关联 ops-agent，然后先普通删除：

```bash
curl -i -sS -X DELETE \
  http://127.0.0.1:8080/api/v1/skills/weather
```

预期 HTTP 409、`reasonCode=SKILL_IN_USE`、`linkedAgents` 完整列出当前关联，且链接和公共包均不变。

用户确认影响范围后才执行：

```bash
curl -sS -X DELETE \
  'http://127.0.0.1:8080/api/v1/skills/weather?force=true'
```

预期服务端重新扫描；响应 `forced=true` 且 `affectedAgents` 是实际执行列表；全部标准链接消失，公共活动目录消失，`.oryxos/archive/.skills/<UTC>-<uuid>/package/` 保留完整包，`archive.yml` 记录来源、forced 与受影响 Agent。删除不存在项返回 404，无副作用。

对无关联 Skill 做普通删除，预期直接归档、`forced=false`。

## 8. 并发与失败边界

使用会在两次 Tool call 间阻塞的 mock provider：

1. 请求 A 取得 ops-agent snapshot 并停在 L2/L3 之间；
2. 请求 B 发起 disable/force delete，必须等待 A 的 lease；
3. A 能继续读取并完成 session save；
4. A 释放后 B 才改变 marker/链接/包；请求 C 看见新状态；
5. B 排队后新增请求不得持续插队读取旧状态。

force delete 在“第 N 个链接已删”和“归档移动失败”故障点注入同进程异常。服务必须只重建本操作已删除且 path 仍为空的标准链接，返回安全稳定错误；随后查询应如实反映文件系统，重试必须重新扫描。不得覆盖外部新建的占位内容。

本 Feature 不验收进程在多路径操作中崩溃后的自动恢复；持久化 journal、启动恢复和跨进程原子性明确留待后续 Feature。

并发新增关联与普通删除/force delete 时，断言 force 在锁内重新扫描，不复用前一次 409 列表。多 Agent 锁获取顺序应通过超时测试证明无死锁。

## 9. Parser、链接与恶意包矩阵

先对 `SKILL.md` parser 执行以下矩阵：

| 输入 | 预期 |
|---|---|
| LF / CRLF / 单独 CR | manifest 与正文完全一致 |
| UTF-8 BOM、文件开头空行 | 可解析且与无 BOM/空行结果一致 |
| closing `---` 有尾随空白 | 可关闭 frontmatter |
| 缺 opening/closing、opening 后无换行 | `MissingFrontmatter` |
| YAML `on/off/yes/no` | YAML 1.2 等价语义下保持字符串 |
| name 1/64 与 version 1/32 字符边界 | 合法；超一字符非法 |
| version 含空白、引号或尖括号 | `InvalidVersion` |
| activation/requires 等于/超过限制 | 边界保持不变；超限确定性过滤/截断并只写安全 WARN |
| `metadata.openclaw.requires` | 只 WARN，不填充顶层 `requires` |
| closing 后只有空白 | `EmptyPrompt` |

| 输入/状态 | 预期 |
|---|---|
| 缺 part、空文件、非 ZIP、缺入口、坏 YAML | 400，零活动残留 |
| `../`、绝对路径、drive/UNC、反斜杠、NUL、规范化重复 | 400 |
| ZIP 内 link、device/FIFO、加密/不支持 entry | 400 |
| ZIP/单文件/展开量/entries/解压比超限 | 413 |
| 公共根下任意同名路径 | 409，不覆盖 |
| link path 被文件、真实目录或非标准 link 占用 | 409，不覆盖/不迁移 |
| 绝对、越界、不同层级、悬空或循环 Agent link | invalid + security WARN，不进入 L1 |
| 公共包内 `SKILL.md`/resource 为 link 或特殊文件 | invalid，不读取目标 |
| 一个公共包损坏 | 该项 invalid，其他 Agent/Skill 正常 |

每个错误响应和日志都不得含 `/Users/`、`/private/`、工作区根、堆栈、包正文或 API key。

## 10. 管理台验收

1. 公共 Skill 页面完成 ZIP 导入、查看详情、全局禁用与启用。
2. Agent 详情 Skill 页签只展示实际软链接关联和链接异常；可从公共列表关联，解除只影响当前 Agent。
3. Agent 创建时选择 Skill 后，详情立即显示实际链接；创建/详情都没有 `example`。
4. 删除有关联 Skill 时，第一次仅调用普通 DELETE；收到 typed 409 后弹窗列出 Agent，明确确认才调用 force。
5. 取消、409、413、500 均保留原行；操作中禁用按钮，200 后再刷新。

## 11. 最终门禁

```bash
mvn clean verify
mvn -pl oryxos-core,oryxos-web,oryxos-boot -am test \
  -Dtest=SkillManifestParserTest,PublicSkillCatalogTest,SkillAssociationServiceTest,AgentLifecycleSkillLinkTest,SkillGraphCoordinatorTest,SkillProgressiveDisclosureE2ETest,SkillForceDeleteIT \
  -Dsurefire.failIfNoSpecifiedTests=false
cd oryxos-web/src/main/frontend
npm test -- --run
npm run build
```

PR 描述必须有醒目的 `Governance Amendment / 治理修订` 独立区块，引用宪章 v2.0.0 Principle IV/VIII，说明公共 Skill 市场例外及公共根、标准相对软链接、无 YAML/数据库关联、无 Tool 扩权四条边界。

## 12. 2026-07-24 实际执行结果

- Parser/YAML/manifest、ZIP 路径与 entry 安全、资源预算、公共 catalog、标准链接、工作区移动、L1/L2/L3、Tool guard、锁与 lease、全局启停、typed 409、强删补偿及 Agent 原子创建均由 core/web/boot 自动化测试覆盖。
- `mvn clean verify`：通过；596 tests，0 failures，0 errors，0 skipped。门禁包含 Spotless、P3C/PMD、Checkstyle、SpotBugs 与模块测试。
- `SkillGlobalStateRestartIT`（integration group）：通过；验证 marker/链接跨重启直接由文件系统恢复语义，无恢复任务。
- 前端 `npm test -- --run`：4 files / 14 tests 全部通过；`npm run build`：通过。
- `git diff --check`：通过；源码与文档均未出现 `.operations/skills` 实现路径。
- 管理台契约由 API/组件/App 测试验证：公共导入/启停、实际关联、创建多选，以及 A（普通删除 typed 409）→ B（明确二次确认 force）顺序。
