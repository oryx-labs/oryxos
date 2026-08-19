# Contract: 知识标准操作契约（SPI）与检索工具

**Date**: 2026-08-19　**Feature**: [spec.md](../spec.md)　**落位**: `oryxos-core/io/oryxos/core/knowledge/`

契约设计三原则（D9）：核心极小化（切分/向量化不进契约）、检索必选管理可选（规避
「契约谎言」）、同步签名（宪法 VII）。

## 1. Java 契约（形状约定，签名以实现 PR 为准）

```java
/** 必选：所有后端插件必须实现。 */
public interface KnowledgeRetriever {
  /** 在指定库范围内检索；结果每条必须带出处（或显式标注出处不可用）。 */
  List<KnowledgeHit> retrieve(KnowledgeQuery query);
}

/** 可选：管理面操作。未声明对应能力的后端不实现，调用在入口被拒绝。 */
public interface KnowledgeAdmin {
  void createBase(String name, String description);
  void deleteBase(String name);                        // 被引用时上层先拒（FR-011）
  void importDocument(String kbName, String relPath);  // 两段式的后台段入口
  void rebuild(String kbName);                         // 双缓冲（FR-024）
  List<DocumentStatus> status(String kbName);
}

/** 后端插件 = 检索 + 能力声明 + 可选管理访问器。 */
public interface KnowledgeBackend extends KnowledgeRetriever {
  String name();                                       // 注册名（local / ragflow / …）
  KnowledgeCapabilities capabilities();
  Optional<KnowledgeAdmin> admin();                    // 能力未声明 ⇒ empty
}

/** 按名显式注册表（宪法 III 同款哲学）；清单 backend: 字段按名解析。 */
public interface KnowledgeBackendRegistry {
  void register(KnowledgeBackend backend);
  Optional<KnowledgeBackend> byName(String name);
  KnowledgeBackend localDefault();                     // 内置本地后端恒可用
}

/** 门面：运行时唯一入口。圈定 Agent 绑定范围 → 逐库路由后端 → 跨库融合全局 top-K。 */
public interface KnowledgeService {
  List<KnowledgeHit> retrieveForAgent(String agentName, String query,
                                      Integer topK, String kbNameOrNull);
  List<KnowledgeBaseInfo> listBases();                 // CLI / REST / 管理台共用
}
```

绑定服务（`KnowledgeBindingService`）契约与 `AgentSkillBindingService` 同构：
`bind / unbind / replaceBindings / inspect / references / reconcile`，非法态
`dangling / escaped / invalid-target / name-mismatch`，全部走真实路径校验。

## 2. 行为契约（参数化契约测试钉死，覆盖本地后端 + 远程桩）

1. **出处强制**：每条命中出处（库名 + 库内相对路径 + 片段位置）非空；远程后端映射
   不到时 `Citation.readable=false` 且位置显式标注「出处不可用」，MUST NOT 返回
   不可用的本地路径（SC-003、FR-015/017）。
2. **切分不跨文档**；片段可回溯原文位置（md/txt 片段序号、PDF 页码）。
3. **范围限定**：检索范围 = 发起 Agent 的绑定库；零绑定调用返回可读错误（不抛栈）；
   限定不存在/未绑定的库同样可读错误（FR-020）。
4. **降级**：embedding 不可用 ⇒ 检索走关键词路并逐条标注 `degraded=true`，对话不
   中断；导入/重建显式失败可重试，MUST NOT 静默丢弃文档（FR-013）。
5. **维度一致性**：chunk 记录的 dim / embedding_model 与当前配置不一致 ⇒ 拒绝混合
   比较并提示重建，不静默返回错误排序（FR-014）。
6. **mock 确定性**：同一文本恒得同一向量，检索排序可重复，CI 可断言（SC-004）。
7. **能力门禁**：对未声明能力的管理调用在入口返回可读拒绝（REST 4xx），MUST NOT
   以 `UnsupportedOperationException` 等运行时异常暴露（FR-006、SC-011）。
8. **多库聚合**：跨库融合后取全局 top-K，结果条数与绑定库数无关（Clarify-Q2）。

## 3. 检索工具 schema（`retrieve_knowledge`，D9 极简两必一选）

```json
{
  "name": "retrieve_knowledge",
  "description": "在当前 Agent 绑定的知识库中检索企业知识，返回带出处的片段。片段是入口：需要完整上下文时按出处路径用 read_file 读取原文。",
  "input_schema": {
    "type": "object",
    "properties": {
      "query":          { "type": "string",  "description": "检索查询（可对用户原话改写）" },
      "limit":          { "type": "integer", "description": "返回片段数上限，默认 5" },
      "knowledge_base": { "type": "string",  "description": "限定单个知识库名；缺省聚合全部绑定库" }
    },
    "required": ["query"]
  }
}
```

工具结果为结构化 JSON（同时是 FR-022 埋点载体，随 `tool_invocations` 落库）：

```json
{
  "query": "磁盘告警怎么处理",
  "hits": [
    { "kb": "ops-manual", "path": "manuals/disk-alert.md", "position": "3",
      "score": 0.87, "degraded": false, "readable": true,
      "content": "……处置步骤……" }
  ],
  "zero_result": false,
  "degraded": false,
  "duration_ms": 42
}
```

## 4. 渐进披露注入契约（`ContextLoader.appendKnowledge()`）

- 每轮注入内容仅为绑定库的 `name + description + 检索指引`（指引固定文案：可用
  `retrieve_knowledge` 检索、可按出处路径读原文）；零绑定零注入（FR-005）。
- 注入与否可参照 `appendOutputDir()` 模式按 `profile.tools()` 是否含
  `retrieve_knowledge` 联动。
- MUST NOT 预载任何文档正文；正文只经工具结果进入 ReAct 上下文。
