# Quickstart: 验证 Agent Skill 三级渐进加载

## Prerequisites

- Java 21、Maven、支持符号链接的本地文件系统。
- 使用临时 `oryxos.root`，不要对真实工作区做归档测试。
- 已启动 OryxOS Web 服务，或使用 MockMvc/MockProvider 集成测试。

## 1. 创建已安装 Skill

```bash
curl -X POST http://localhost:8080/api/v1/skills \
  -H 'Content-Type: application/json' \
  -d '{"name":"report-format","description":"结构化报告规范","body":"正文标记 SKILL_BODY_SENTINEL"}'
```

预期：`.oryxos/skills/report-format/SKILL.md` 存在，`GET /api/v1/skills` 可见该实体。

## 2. 直接创建 Agent 并绑定

```bash
curl -X POST http://localhost:8080/api/v1/agents \
  -H 'Content-Type: application/json' \
  -d '{"name":"reporter","description":"生成日报","skillBindings":["report-format"]}'
```

预期文件系统：

```text
.oryxos/agents/reporter/skills/report-format -> ../../../skills/report-format
```

`AGENT.md` 中不得出现 top-level `skills:`，Agent 目录不得出现复制的 `SKILL.md`。

## 3. 查询绑定与 catalog

```bash
curl http://localhost:8080/api/v1/agents/reporter/skills
curl 'http://localhost:8080/api/v1/skills/catalog?visibility=all'
```

绑定响应只含 name、description、Agent 本地绝对 `skillFile` 和 issues，不含 body。catalog 响应可以
包含公共/私有标签，但只有 `installed=true` 的合法项可用于生成绑定。

## 4. 验证 Level 1：元数据常驻、正文缺席

用 MockProvider 捕获首次 `ProviderRequest.systemPrompt`，或运行目标测试：

```bash
mvn -pl oryxos-core -Dtest=ProgressiveDisclosureTest,ContextLoaderTest test
```

预期：

- 包含 `report-format`、`结构化报告规范` 和
  `.oryxos/agents/reporter/skills/report-format/SKILL.md` 的绝对路径。
- 不包含 `SKILL_BODY_SENTINEL`、references、templates 或 scripts 内容。
- 未绑定 Skill 完全不可见；无绑定时没有空 Skill 标题。

## 5. 验证 Level 2/3：工具按需读取

让 MockProvider 首轮返回：

```text
read_file(<Agent 本地绝对路径>/skills/report-format/SKILL.md)
```

预期第二轮：

- `SKILL_BODY_SENTINEL` 仅作为 Tool Result 出现在 history。
- `tool_invocations` 有对应 `read_file` 成功记录。
- 两次 provider chat 前都重新扫描绑定；在两轮之间更新 description 或解绑，第二轮 system prompt
  立即反映新状态。
- 若正文引用 `references/example.md` 或 `scripts/check.py`，只有模型再次调用 `read_file`/`shell` 后
  对应内容或输出才进入后续 history。

## 6. 验证作者模型自动补充但不持久化 sidecar

```bash
curl -X POST http://localhost:8080/api/v1/agents/reporter-2/generate-files \
  -H 'Content-Type: application/json' \
  -d '{"description":"每天调研并生成日报","requiredSkills":["report-format"]}'
```

用 catalog/author stub 让模型补充 `web-research`。预期响应分别给出 required、suggested 和二者并集
bindingSkills；生成的 `AGENT.md` 不含 `skills:`。把 files + bindingSkills 发到保存端点后，两个 Skill
均成为软连接。模型返回列表外或未安装名称时必须被拒绝，不能创建链接或触发下载。

## 7. 验证旧 frontmatter 原子迁移

在启动前准备旧 Agent：

```yaml
---
name: legacy-agent
provider:
  name: mock
  model: mock
skills:
  - report-format
---
旧正文
```

重启后预期：

- `skills/report-format` 链接存在。
- `AGENT.md` 其它 frontmatter 与正文保持，只有 top-level `skills:` 被移除。
- 再次重启不产生额外变化。

再构造一个引用不存在 Skill 的旧 Agent。预期该 Agent 原始文件字节不变、没有部分链接、问题 API
返回 `STALE_REFERENCE`，其它 Agent 正常迁移和启动。

## 8. 验证活跃/归档引用保护与 Skill 归档

绑定存在时：

```bash
curl -X DELETE http://localhost:8080/api/v1/skills/report-format
```

预期 `409`，响应结构化列出活跃引用。将 Agent 归档后再次请求，仍为 `409`，引用状态变为
`ARCHIVED`；归档 Agent 的 `../../../skills/report-format` 仍可解析。

在所有活跃与归档引用都解绑后再次删除，预期：

- `.oryxos/skills/report-format` 消失。
- `.oryxos/archive/skills/report-format-<timestamp>/` 完整保留 `SKILL.md` 和附属资源。
- `GET /api/v1/skills`、catalog installed 交集和所有 prompt 均不再包含它。

## 9. 验证一致性与真实路径沙箱

```bash
curl http://localhost:8080/api/v1/skills/binding-issues
mvn -pl oryxos-core,oryxos-tool,oryxos-web test
```

必须覆盖：

- dangling、escaped、invalid-target、name-mismatch、stale-reference 五类问题。
- 白名单根内链接指向根外文件时 read/write/download 全部拒绝。
- 父目录链接逃逸下的不存在写目标不在根外产生文件。
- dangling link、多跳 link 和 link cycle fail closed。
- 合法 Agent Skill 链接在白名单包含整个 `.oryxos` 时允许读取；只允许 Agent 子目录而未允许共享
  Skill 根时拒绝。
- Workspace tree 把链接作为叶节点，不递归越界或死循环。

## 10. Quality gates

```bash
cd oryxos-web/src/main/frontend && npm run build
cd ../../../..
mvn test
mvn verify
git diff --check
```

全部通过后，再按 [HTTP contract](contracts/skill-bindings.md) 核对响应字段和状态码。

## 11. 最终验证记录（2026-07-27）

- `npm run build`：通过；Vite 生产构建完成，15 个模块转换成功。
- `mvn test`：通过；全仓 397 个测试，0 failure、0 error、0 skipped。
- `mvn verify`：通过；Spotless、P3C/PMD、Checkstyle、SpotBugs/Find Security Bugs 均为 0
  violation。仓库当前配置明确输出 `Skipping dependency-check`，因此本次没有生成 OWASP 依赖漏洞报告。
- 三级 prompt 与实时刷新：`ProgressiveDisclosureTest`、`ContextLoaderTest`、
  `ReActLoopSkillDisclosureTest` 覆盖仅注入元数据/入口、按需 `read_file`、工具审计及下一轮刷新。
- catalog、作者建议、绑定 CRUD 与完整归档：core 生命周期测试和 Web MockMvc 合同测试覆盖 PUBLIC/PRIVATE
  查询、required ∪ suggested、失败回滚、活跃/归档引用 409 与无引用完整目录归档。
- legacy 与一致性：迁移及启动顺序测试覆盖单 Agent 原子迁移、幂等、坏 Agent 隔离，以及 dangling、
  escaped、invalid-target、name-mismatch、stale-reference 五类问题。
- 真实路径边界：core/tool/web 测试覆盖最终链接、父链接、多跳、dangling、链接环、不存在写目标、
  Workspace tree 叶节点和 Agent 本地合法 Skill 链接。
