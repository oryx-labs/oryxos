# Contract: OryxOS Agent Skill Package v1

本契约定义 REST 上传和手工放入 Agent 目录的受管 Skill 包。格式对齐开放的 [Agent Skills Specification](https://agentskills.io/specification)，并增加 OryxOS 的导入安全约束。

## 1. 逻辑目录

```text
weather/
├── SKILL.md                # required
├── scripts/                # optional
├── references/             # optional
└── assets/                 # optional
```

第一版一次只能导入一个 Skill。ZIP 可以是：

```text
SKILL.md                    # shape A: package content at ZIP root
scripts/...
```

或：

```text
weather/SKILL.md            # shape B: exactly one wrapper directory
weather/scripts/...
```

shape B 的 wrapper 名必须等于 metadata `name`。不得出现第二个顶层文件/目录、嵌套 Skill 或空包。

## 2. SKILL.md

文件必须以 YAML frontmatter 开头：

```markdown
---
name: weather
description: 查询天气并根据天气给出出行建议；用户询问天气、穿衣或出行时使用。
license: Apache-2.0
compatibility: Requires read_file; optional shell scripts use bash.
metadata:
  author: example-team
  version: "1.0"
allowed-tools: read_file shell
---

# Weather Skill

按以下步骤处理……
```

字段规则：

| 字段 | 必填 | 规则 | 运行时用途 |
|---|---|---|---|
| `name` | 是 | 1–64；`^[a-z0-9]+(?:-[a-z0-9]+)*$`；等于包目录名 | L1 + 身份 |
| `description` | 是 | 1–1024 字符；说明做什么和何时触发 | L1 |
| `license` | 否 | String | 管理详情 |
| `compatibility` | 否 | String，最多 500 字符 | 管理详情 |
| `metadata` | 否 | String→String map | 管理详情/保留 |
| `allowed-tools` | 否 | String | 仅展示；不授予 Tool |

未知 frontmatter 字段允许存在并可被忽略，但不得触发自动代码、工具注册或权限变化。正文建议控制在 500 行以内；OryxOS v1 的硬文件上限为 256 KiB。

frontmatter 必须通过安全 YAML 子集：禁止 custom tag、duplicate key、anchor/alias，最大嵌套 8 层，code points 不得超过 64 KiB 预算；`metadata` 的键和值都必须是 String。解析器使用 SnakeYAML `SafeConstructor`，不能对上传内容调用默认的任意类型构造器。

## 3. L2/L3 引用规则

- `SKILL.md` 是 L2，只有模型根据 L1 判断命中后才通过 `read_file` 读取。
- `SKILL.md` 中引用的相对路径以该 Skill 目录为基准，例如 `references/api.md`。
- reference/assets 只在正文步骤需要时读取；scripts 只在 Profile 已显式允许 `shell` 且命令白名单通过时执行。
- 包不得假设 OryxOS 会读取所有资源、自动运行初始化脚本或安装依赖。
- `allowed-tools` 不会给 Agent 增加任何工具；作者必须在 Agent 的 `AGENT.md` Profile 中显式声明实际需要的 Tool。

## 4. ZIP 安全约束

每个 entry 必须满足：

- 相对 POSIX 路径，只使用 `/`；禁止开头 `/`、Windows drive、UNC、反斜杠和 NUL。
- 禁止空段、`.`、`..`；NFC 规范化和大小写折叠后仍须唯一。
- 最多 512 字符、8 层深度。
- entry 声明 Unix mode 时只允许 regular file/directory；符号链接、device、FIFO、socket 一律拒绝。Windows/FAT ZIP 常见的 mode=0 不是特殊文件，按 `isDirectory`/普通 entry 接受后仍只以 `CREATE_NEW` 物化普通文件。加密条目和不支持压缩方法拒绝；ZIP 没有统一硬链接契约，OryxOS 不恢复任何 link semantics。
- OryxOS 不恢复 ZIP 内 owner、Unix mode 或 executable bit；所有文件以新普通文件创建。
- staging、Agent、skills、发布目标以及删除时的 `archive/.skills/<agent>` 全父链都用 `NOFOLLOW_LINKS` + real-path containment 校验，不能借工作区内预置目录链接把发布/归档引到工作区外。
- 按扩展名拒绝 `.zip/.jar/.war/.ear/.tar/.tgz/.gz/.bz2/.xz/.7z/.rar/.class/.so/.dylib/.dll/.exe`；无论扩展名为何，检测到 ZIP、gzip、bzip2、xz、7z、RAR、tar (`ustar` at offset 257)、Java class (`CAFEBABE`)、ELF、PE (`MZ`) 或 Mach-O magic 也拒绝。脚本源码与静态 assets 必须落在总量限制内。
- 任何位置不得包含 OryxOS 保留文件 `.oryxos-disabled`、`.oryxos-origin.yml`。

默认限制：ZIP 10 MiB、解压总量 25 MiB、单文件 5 MiB、`SKILL.md` 256 KiB、frontmatter 64 KiB、128 entries、100:1 解压比。

校验实际读取字节，不信任 ZIP header 中的 size。任何一项失败都使整个导入失败，活动目录不产生文件。

## 5. 导入身份与冲突

- Skill 身份只来自已校验的 frontmatter `name`，不来自上传文件名。
- 导入前同时检查 enabled、disabled 和 invalid 的同名受管目录；任何同名都返回 409，不覆盖。
- 上传成功后 OryxOS 添加 `.oryxos-origin.yml`，默认不添加 `.oryxos-disabled`，即下一次请求可发现。
- `originalFilename` 只保存清洗后的 basename，不保存浏览器本地路径。

## 6. 手工维护与 legacy

| 形态 | 分类 | L1 | 管理 API |
|---|---|---:|---:|
| `skills/weather/SKILL.md` | managed workspace Skill | 是（合法且 enabled 时） | 是 |
| `skills/weather.md` | legacy flat instruction | 否 | 否 |
| `skills/SKILL.md` | legacy/unmanaged file | 否 | 否 |
| 含 OryxOS marker 的 `skills/weather/` 缺 SKILL.md | invalid managed candidate | 否 | 可查看/禁用/删除 |
| 无 SKILL.md/marker 的 `skills/references/` | legacy/unmanaged directory | 否 | 否 |

手工包没有 `.oryxos-origin.yml` 时，来源显示 `workspace`。若存在保留文件，`.oryxos-disabled` 必须是零字节普通文件，`.oryxos-origin.yml` 必须是至多 4 KiB 的安全 YAML 普通文件。根目录 symlink 不跟随、不列为候选并 WARN；真实包目录内的入口/后代均须以 `NOFOLLOW_LINKS` 验证为 Skill 根内普通文件/目录，任一链接、特殊文件或损坏的保留文件都会令候选 invalid。OryxOS 不自动迁移、改写或删除 legacy 文件/目录；但同名目录即使 unmanaged 也会阻止 import 覆盖。

## 7. 信任声明

结构校验防止 ZIP Slip、链接和资源耗尽，不判断指令是否恶意。Skill 能诱导 Agent 使用 Profile 已有的 Tool 权限，应像审查代码一样审查 `SKILL.md`、scripts 和 references，仅导入可信来源。
