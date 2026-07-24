# Contract: `SKILL.md` Parser and Manifest

本契约定义公共导入、catalog 扫描、重新启用校验共同使用的唯一 `SKILL.md` 解析语义。Java 实现可以采用不同内部 API，但顺序、边界和领域结果必须等价。

## 1. 输入规范化与 frontmatter 定位

解析器按以下顺序处理 UTF-8 文本：

1. 把 `\r\n` 替换为 `\n`，再把剩余 `\r` 替换为 `\n`。所有后续字节/字符偏移都基于规范化文本。
2. 仅从文件起始位置移除一个 UTF-8 BOM `\uFEFF`。
3. 仅移除文件开头连续的换行字符；不吞掉其它空白。
4. 当前文本首三个字符必须为 `---`，否则返回 `MissingFrontmatter`。
5. opening `---` 所在行必须有换行；解析从该换行后的第一个字符开始。无换行返回 `MissingFrontmatter`。
6. 逐行寻找 closing delimiter。只有某行 `trim()` 后逐字等于 `---` 时才结束 frontmatter，允许该行前后有空白；找不到返回 `MissingFrontmatter`。
7. closing 行之前的切片是 `yamlText`。
8. closing 行之后跳过该行换行，再仅移除正文开头连续换行，得到 `promptContent`。
9. `promptContent.trim().isEmpty()` 时返回 `EmptyPrompt`。

opening fence 所在行除前三个 `-` 外的其它内容不作为 YAML；它仍必须以换行结束。正文中的 `---` 只有独占一行时才可能被识别为 closing fence，而且扫描在第一个 closing fence 处停止。

## 2. YAML 安全语义

- 使用 SnakeYAML safe loader，并配置为 YAML 1.2 等价隐式类型语义；不得用 YAML 1.1 的 `yes/no/on/off` 布尔隐式转换。
- 禁止 custom tag、duplicate key、anchor/alias，并限制 code points、嵌套深度、集合大小和 frontmatter 总量。
- YAML 语法或类型映射失败统一返回 `InvalidYaml(safeMessage)`；safeMessage 不含绝对路径、正文或 secret。
- 未识别字段可以忽略，但不能触发 Tool 注册、权限变化、网络访问、脚本执行或任意类型构造。

如果选用的 SnakeYAML 版本不能直接切换 YAML 1.2 resolver，实现必须提供等价的受限 resolver 并以 `yes/no/on/off` 字符串测试锁定行为。

## 3. Manifest 字段

```text
SkillManifest {
  name: String                      // required
  description: String               // required
  version: String?                  // optional
  license: String?
  compatibility: String?
  metadata: Map<String, String>
  allowedTools: String?
  activation: ActivationCriteria?
  requires: GatingRequirements?
}

ActivationCriteria {
  keywords: List<String>
  excludeKeywords: List<String>
  patterns: List<String>
  tags: List<String>
  maxContextTokens: Integer?       // informational in this Feature
  setupMarker: String?
}

GatingRequirements {
  bins: List<String>
  env: List<String>
  config: List<String>
  skills: List<String>
}
```

- `name`: `^[a-zA-Z0-9][a-zA-Z0-9._-]{0,63}$`。开启 `validateName` 时失败返回 `InvalidName{name}`；发布时还必须等于包目录 basename。
- `description`: trim 后 1–1024 字符。
- `version`: 缺失可接受；出现时必须匹配 `^[a-zA-Z0-9._\-+~]{1,32}$`，失败返回 `InvalidVersion{version}`。该 grammar 明确拒绝引号、空白和尖括号，防止未来 XML attribute breakout。
- `compatibility`: 最多 500 字符。
- `metadata`: 有界 String→String 展示值。
- `allowed-tools`: 只展示，不能修改 Agent 的显式 Tool 权限。
- `activation` / `requires`: 反序列化完成后分别调用统一的 `enforceLimits()`，语义见下节。

`SkillManifestLimits` 集中定义并由边界测试锁定以下兼容上限，不允许各入口自行设值：

| 字段 | 规则 |
|---|---|
| `activation.keywords` | 移除长度小于 3 的项，再保留前 20 项 |
| `activation.exclude_keywords` | 移除长度小于 3 的项，再保留前 20 项 |
| `activation.patterns` | 保留前 5 项；本 Feature 不执行这些 regex |
| `activation.tags` | 移除长度小于 3 的项，再保留前 10 项 |
| `activation.setup_marker` | 超过 256 UTF-8 bytes 或包含 `..` 时清除 |
| `requires.skills` | 保留前 10 项 |

过滤、清除或截断不使整个 manifest 失败，但必须为该 Skill 写一次不含原值的 `MANIFEST_LIMITS_APPLIED` WARN。`requires.bins/env/config` 受 frontmatter 总字节、YAML code points、集合与嵌套的通用安全预算约束，本 Feature 不执行 gating、不查 PATH/env/config，也不自动安装依赖。所有校验在线性遍历中完成。

## 4. Legacy shape

解析器在安全反序列化后检查原始 `yamlText`。发现 `metadata.openclaw.requires` 时写一次结构化 WARN：

```text
event=skill.manifest.legacy
skill=<validated-or-safe-placeholder>
reasonCode=LEGACY_OPENCLAW_REQUIRES
```

该 shape 不阻断其它合法字段的解析。它被 YAML 映射忽略后，顶层 `manifest.requires` 保持其真实反序列化值（通常为空）；实现不得把 legacy 嵌套值静默提升为顶层 `requires`。日志不得包含完整 YAML 或绝对路径。

## 5. 返回与稳定错误

成功结果：

```text
ParsedSkill(manifest, promptContent)
```

稳定错误至少包括：

| Code | 条件 |
|---|---|
| `MissingFrontmatter` | opening/closing fence 缺失或 opening 行未终止 |
| `InvalidYaml` | YAML 语法、类型或安全约束失败 |
| `InvalidName` | name grammar 失败 |
| `InvalidVersion` | version grammar 失败 |
| `EmptyPrompt` | closing fence 后正文为空白 |

同一输入经导入、catalog 重扫或 enable 复验必须产生相同 manifest、prompt 和错误 code。catalog 列表把单包错误转为该项 `INVALID`，不得拖垮其它 Skill。

## 6. 最小一致性矩阵

- LF、CRLF、单独 CR 三种行尾解析结果相同。
- 有/无 BOM、有/无开头空行解析结果相同。
- closing `---` 可有尾随空白；`---x` 不能关闭 frontmatter。
- 缺 opening、opening 后无换行、缺 closing 分别得到 `MissingFrontmatter`。
- YAML 1.2 下 `on/off/yes/no` 保持字符串。
- name 长度 1/64 合法，0/65 非法；首字符和允许字符边界均有测试。
- version 长度 1/32 合法，0/33、空白、引号、`<`/`>` 非法。
- activation/requires 每个上限的等于边界保持不变、超一确定性截断；短项与非法 setup_marker 确定性清除并只 WARN。
- legacy shape 只 WARN，不填充顶层 requires。
- 只有空白正文得到 `EmptyPrompt`。
