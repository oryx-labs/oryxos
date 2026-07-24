# Contract: OryxOS Public Skill Package v1

本契约定义公共 Skill 包与 Agent 标准关联。包形态对齐 Agent Skills 的目录式渐进披露，但 OryxOS 只把经过显式信任导入的包发布到 `.oryxos/skills/`。

## 1. 公共包形态

```text
.oryxos/skills/weather/
├── SKILL.md                # required, L2
├── references/             # optional, L3
├── scripts/                # optional, L3
└── assets/                 # optional, L3
```

单次 ZIP 只能包含一个 Skill，接受两种 shape：ZIP 根直接包含 `SKILL.md`，或恰有一个与 metadata name 相同的 wrapper 目录。禁止第二个顶层目录、嵌套 Skill、空包与保留文件。

## 2. SKILL.md

```markdown
---
name: weather
description: 查询天气并给出出行建议；用户询问天气、穿衣或出行时使用。
version: 1.0.0
license: Apache-2.0
compatibility: Requires read_file; optional scripts require shell.
metadata:
  author: example-team
allowed-tools: read_file shell
---

# Weather

先按任务需要读取 `references/rules.md`。
```

字段规则：

| 字段 | 必填 | 规则 | 用途 |
|---|---|---|---|
| `name` | 是 | 1–64；`^[a-zA-Z0-9][a-zA-Z0-9._-]{0,63}$`；等于公共目录名 | 身份 + L1 |
| `description` | 是 | trim 后 1–1024 字符，说明做什么/何时使用 | L1 |
| `version` | 否 | 1–32；`^[a-zA-Z0-9._\-+~]{1,32}$` | 展示及未来安全插值 |
| `license` | 否 | String | 管理展示 |
| `compatibility` | 否 | 最多 500 字符 | 管理展示 |
| `metadata` | 否 | String→String | 管理展示 |
| `allowed-tools` | 否 | String | 只展示，不授予权限 |
| `activation` | 否 | 结构化对象，反序列化后执行数量/字符串/嵌套限额 | 激活提示，不自动执行 |
| `requires` | 否 | 结构化对象，反序列化后执行数量/字符串/嵌套限额 | 兼容性声明，不授予权限 |

完整 frontmatter 定位、YAML 1.2 等价解析、legacy warning、字段限额和稳定错误见 [parser-manifest.md](./parser-manifest.md)。未知字段可忽略，但不得触发工具注册、代码执行或权限变化。

## 3. 标准 Agent 关联

系统关联 `weather` 到 `ops-agent` 时只能创建：

```text
.oryxos/agents/ops-agent/skills/weather -> ../../../skills/weather
```

契约要求：

- 链接 basename、公共目录名和 metadata name 都是 `weather`；
- link text 必须逐字为 `../../../skills/weather`，不得写绝对路径或 canonical path；
- `agents/ops-agent/skills` 必须是真实目录，所有父链用 `NOFOLLOW_LINKS` 验证；
- 创建使用临时链接 + 原子 rename；已存在相同标准链接时幂等，其他文件、目录或链接均返回冲突；
- 解除关联只删除复验后的标准链接，不跟随目标，不删除公共包；
- 工作区整体移动后链接仍须有效；复制/恢复到新位置不需要重写链接。

手工绝对链接、层级不同但最终指向相同目标的链接、悬空链接、越界链接、真实目录和嵌套链接都不是标准关联，不进入 L1，也不会被 force delete 误删。

## 4. L1/L2/L3

- 每次顶层请求扫描当前 Agent 的标准链接，只选择目标公共包 `enabled` 且内容合法的项。
- L1 只渲染 `name`、`description` 和可供 `read_file` 使用的入口路径，不包含正文或 resources。
- OryxOS 不自动预载或执行 L2/L3。模型命中后必须显式调用既有 `read_file` 读取公共目标内的 `SKILL.md`（L2）；只有 L2 指令要求且模型再次显式调用已授权 Tool 时，才读取 references/assets 或执行 scripts（L3）。
- `allowed-tools` 绝不修改 `AGENT.md` 的显式 Tool 权限；L2/L3 仍经过 ToolExecutor、SandboxChecker 和审计。
- `AGENT.md/AGENTS.md` 中出现 Skill 名不构成关联，也不触发 eager loading。

## 5. ZIP 与文件系统安全

每个 entry 必须是相对 POSIX 路径：禁止绝对路径、drive/UNC、反斜杠、NUL、空段、`.`、`..`，并在 NFC/大小写折叠后保持唯一。默认最多 512 path chars、8 层、128 entries。

只允许普通文件/目录；拒绝 symlink、hardlink 语义、device、FIFO、socket、加密和不支持的压缩方法。不恢复 owner、mode 或 executable bit。按扩展名与 magic 拒绝嵌套归档、Java class 与本机二进制。实际读取字节校验 ZIP 10 MiB、解压总量 25 MiB、单文件 5 MiB、`SKILL.md` 256 KiB、frontmatter 64 KiB、100:1 解压比，不信任 header size。

staging、公共 Skill 根、发布目标、Agent 关联父目录与归档父链都执行 `NOFOLLOW_LINKS` + containment 校验。任何失败都不改变活动公共包或关联。

保留文件 `.oryxos-disabled` 与 `.oryxos-origin.yml` 只能由 OryxOS 创建；上传中出现即拒绝。disabled marker 必须是零字节普通文件，origin 必须是有界安全 YAML。

## 6. 身份、冲突与来源

- 身份只来自校验后的 frontmatter `name`，不来自上传文件名或 GitHub 目录名。
- 公共根下同名 enabled、disabled、invalid 或 unmanaged 路径都返回 409，绝不覆盖。
- 本 Feature 的新增导入契约是本地 multipart ZIP。既有 GitHub 导入若保留，必须复用同一个安全校验、staging 和原子发布路径，不能绕过包契约。
- 上传成功默认 enabled；来源写入清洗后的 `.oryxos-origin.yml`，不保存浏览器绝对路径或凭证。

## 7. Legacy 与信任边界

`.oryxos/skills/<name>/SKILL.md` 才是公共受管候选。旧的 Agent 私有真实目录、`skills/*.md` 和 `AGENT.md skills:` 都是 unmanaged：不自动迁移、不进入 L1，也不由公共删除接口修改。Agent `skills/` 下非标准链接作为 invalid association 展示但不跟随。

结构校验只能防止 ZIP Slip、链接攻击与资源耗尽，不能证明指令、references 或 scripts 善意。导入是管理员的显式信任动作，必须像审查代码一样审查整个包。
