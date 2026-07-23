# Quickstart: 验收 Agent Skill 渐进加载与管理

本页用于实现完成后的本地验收。示例 Agent 名为 `ops-agent`，服务端口为 8080。Skill 包属于可执行指令级内容，只使用自己审查过的测试包。

## 1. 前置条件

- JDK 21：`java -version`
- Node/npm 已可用（仓库提交 `package-lock.json`，Maven 也使用 npm 构建管理台）
- 已有 `.oryxos/agents/ops-agent/AGENT.md`
- Agent Profile 显式包含 `read_file`；需要运行脚本时另显式包含 `shell`

不要因为 `allowed-tools` 出现在 Skill frontmatter 就给 Agent 自动加权限。

## 2. 构造一个最小 Skill ZIP

创建目录 `weather/`，其中 `SKILL.md` 内容为：

```markdown
---
name: weather
description: 查询天气并给出穿衣和出行建议；用户询问天气、温度或穿衣时使用。
license: Apache-2.0
metadata:
  author: local-test
---

# Weather

当用户询问天气时，先读取 `references/rules.md`；不要读取未被任务需要的资源。
```

再创建 `weather/references/rules.md`，写入唯一标记 `MATCHED_WEATHER_RULES_012`。在同级另建第二个合法 Skill，并在正文/资源中写不同标记，用于证明未命中包没有被读取。

从 `weather` 的父目录打包，确保 ZIP 内只有一个顶层 `weather/`：

```bash
zip -r weather.zip weather
```

## 3. 构建与启动

```bash
mvn clean verify
JAR=oryxos-boot/target/oryxos-boot-1.0.0-SNAPSHOT.jar
java -jar "$JAR" serve --port 8080
```

前端单独开发时：

```bash
cd oryxos-web/src/main/frontend
npm install
npm run dev
```

生产验收以同进程 `/admin/` 页面为准。

## 4. REST 管理闭环

### 导入

```bash
curl -sS -X POST \
  -F 'file=@weather.zip;type=application/zip' \
  http://127.0.0.1:8080/api/v1/agents/ops-agent/skills
```

预期：HTTP 200，`data.status=enabled`、`data.source=upload`、`data.entrypoint=skills/weather/SKILL.md`；活动目录一次性出现完整包，没有 staging 残留。

### 列表与详情

```bash
curl -sS http://127.0.0.1:8080/api/v1/agents/ops-agent/skills
curl -sS http://127.0.0.1:8080/api/v1/agents/ops-agent/skills/weather
```

预期：列表按 name 排序；路径都是 Agent 相对路径；响应没有本机工作区绝对路径，也不返回正文。

### 禁用与启用

```bash
curl -sS -X PUT \
  -H 'Content-Type: application/json' \
  -d '{"enabled":false}' \
  http://127.0.0.1:8080/api/v1/agents/ops-agent/skills/weather

curl -sS -X PUT \
  -H 'Content-Type: application/json' \
  -d '{"enabled":true}' \
  http://127.0.0.1:8080/api/v1/agents/ops-agent/skills/weather
```

预期：先返回 disabled，再返回 enabled；服务重启后状态与 marker 一致；不需要重启才生效。

禁用后的负向发现测试应新建 Session：旧 Session 已经保存的 `read_file` Tool result 仍属于对话历史，不会被管理操作追溯删除。

### 删除

```bash
curl -sS -X DELETE \
  http://127.0.0.1:8080/api/v1/agents/ops-agent/skills/weather
```

预期：`data=null`；活动目录消失；`.oryxos/archive/.skills/ops-agent/<UTC>-<uuid>/` 下有写明 weather 的 `archive.yml` 和完整 `package/`。

## 5. 渐进加载验收

用 mock provider 自动化测试或可观察的本地 provider 发起只命中 weather 的请求：

1. 第一轮 system context 同时包含两个 Skill 的 name/description/entry。
2. 第一轮不包含任一 `SKILL.md` 正文标记或 reference 标记。
3. 模型只对 weather entry 发出 `read_file`。
4. 读取正文后，只有确实需要时才读取 `weather/references/rules.md`。
5. `MATCHED_WEATHER_RULES_012` 最终出现；未命中 Skill 的唯一标记从未出现。
6. `tool_invocations` 仍记录这两次 `read_file`，没有 `use_skill` 调用。

Profile 没有 `read_file` 时重跑：L1 可显示 Skill，但 OryxOS 不自动扩权，Agent 应得到明确的不可加载提示。

## 6. 并发一致性验收

使用一个会在两轮 Tool call 之间阻塞的 mock provider：

1. 请求 A 开始并取得 weather 快照；
2. 请求 B 调用 disable 或 DELETE；
3. B 在写租约处等待，活动文件不变；
4. A 能继续读取 L2/L3 并完成会话保存；
5. A 释放租约后 B 完成；
6. 请求 C 开始时不再看见 weather。

再让写操作先排队，然后并发提交请求 C；fair lock 下 C 不应越过 B 继续读取旧状态。

## 7. 恶意包矩阵

逐项上传并断言错误与零活动残留：

| 输入 | 预期 |
|---|---|
| 缺 file part / 空文件 / 非 ZIP | 400 |
| 缺 `SKILL.md`、坏 YAML、name 与目录不一致 | 400 |
| `../escape`、绝对路径、Windows drive、反斜杠、NUL | 400 |
| 大小写/NFC 后重复路径、重复 ZIP entry | 400 |
| Unix symlink、device/FIFO、加密或不支持条目 | 400 |
| ZIP/单文件/解压总量/entry 数/解压比超限 | 413 |
| enabled/disabled/invalid 同名 Skill 或同名 unmanaged 目录 | 409 |
| 不存在 Agent 或 Skill | 404 |

每个失败响应都应使用统一信封，且不含 `/Users/`、`/private/`、工作区根、堆栈或包正文。

## 8. Legacy 与人工改动回归

- 保留一个旧文件 `skills/report-format.md`：它仍可由 AGENT 正文显式指引 `read_file`，但不出现在 Skill 管理列表或 L1。
- 手工创建 `skills/broken/SKILL.md` 且写入非法/未闭合 frontmatter：Agent 启动和其他 Skill 正常；列表显示 broken=invalid。
- 手工创建无 `SKILL.md`/marker 的 `skills/references/`：它按 legacy/unmanaged 忽略，不得被管理 API 误删。
- 手工让 Skill 根目录成为 symlink：catalog 不跟随、不列出并写安全 WARN；让真实包内的 `SKILL.md` 或 resource 成为 symlink：列表显示 invalid、L1 不出现，详情不得读取链接目标。
- 修复 broken 后下一次扫描恢复；无需 WorkspaceWatcher 事件或服务重启。
- 手工制造超过 12,000 字符的 L1 目录：snapshot 按 name 确定性保留完整条目并 WARN 省略数，管理页显示 `catalogIncluded=false`。

## 9. 管理台验收

打开 `http://127.0.0.1:8080/admin/`：

1. 进入 ops-agent 详情并切到 Skill 页签；
2. 上传 weather.zip，观察 loading、成功提示和新行；
3. 连点操作时按钮保持禁用，不重复提交；
4. 禁用、刷新页面和重启服务，状态仍 disabled；
5. 启用后状态恢复；
6. 删除需二次确认，服务端成功后才移除行；
7. 模拟 409/413/500，原行不被乐观改掉且错误可读。

## 10. 最终门禁

US1 渐进披露核心可单独复现：

```bash
mvn -pl oryxos-core,oryxos-web,oryxos-boot -am test \
  -Dtest=MarkdownFrontmatterTest,SkillMetadataReaderTest,SkillContentValidatorTest,AgentSkillCatalogTest,AgentSkillCoordinatorTest,SkillProgressiveDisclosureE2ETest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -DexcludedGroups=
```

本机若临时使用尚未受当前 Byte Buddy 版本正式支持的 JDK 26，可在该验证命令中额外传入
`-Dnet.bytebuddy.experimental=true`；正式项目门禁仍以 JDK 21 为准。

```bash
mvn clean verify
mvn -pl oryxos-boot -am test \
  -Dtest=SkillRestartRecoveryIT,SkillManagementE2ETest,SkillProgressiveDisclosureE2ETest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -DexcludedGroups=
cd oryxos-web/src/main/frontend
npm test -- --run
npm run build
```

同时检查日志：每个已进入 management service 的 import/enable/disable/delete 恰一条 `event=skill.management`；Web 层提前拒绝的缺 part/坏 JSON/上传超限只有 Web 错误日志。任何日志都不得出现 Skill 正文、本机绝对路径或敏感配置。
