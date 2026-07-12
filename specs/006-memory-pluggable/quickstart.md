# Quickstart: Memory 验证指引

**Date**: 2026-07-12

## 自动化验收

```bash
mvn clean verify        # 全量门禁（完成定义）
mvn test -pl oryxos-memory -am    # 本节测试 + 前序回归
```

关键回归（课件两个参数化，三档统一跑）：
- `截断只裁归档区_核心记忆一字不能少`（@MethodSource allStores，md/sqlite/mem0替身三档全过）
- `写入后立刻可读_不允许有缓存`（同上）

## 回归证据（跨节契约 + 依赖方向）

```bash
mvn test -pl oryxos-core,oryxos-provider,oryxos-storage,oryxos-tool -am   # 16~20 节全绿（PromptBuilder 扩展非破坏）
grep -rn "io.oryxos.memory" oryxos-core/src/main   # 预期无命中（core 不依赖 memory；只 core.memory 接口被 memory 实现）
```

## 三档切换（人工）

```bash
# application.yml: memory.backend 依次设 markdown / sqlite，各跑一次对话
java -jar oryxos-boot/target/*.jar chat   # save_memory 写入、下一轮 buildContext 带上——同体感、底层已换
```

## 人工项（课件"五、做完怎么验"）

1. 三档切换同体感（markdown / sqlite 各一次）。
2. Mem0 档真连一次（部署自托管 Mem0，`memory.backend: mem0` + `${MEM0_BASE_URL}`，验证 save 进 Mem0、recall 语义召回）——依赖真 server，测不了。
3. 真模型跨会话：对话里说一句值得记的，Agent 调 save_memory；开新会话核心记忆在场。
4. MEMORY.md 可写、USER.md 只读（code review 确认无写 USER.md 路径）。
