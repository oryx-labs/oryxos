# PR 计划：飞书卡片流式输出

## 提案概述

**目标**：为 OryxOS 飞书渠道添加类似 OpenClaw 的流式反馈体验

**用户体验**：
1. 用户发消息 → 机器人立即加⌨️表情（已读确认）
2. Agent 处理中 → 发送/更新飞书卡片，实时显示思考过程
3. 处理完成 → 移除表情，更新卡片为最终结果

**价值**：
- 即时反馈：用户知道消息被看到了
- 过程透明：看得到 Agent 在做什么（思考/工具调用）
- 结果清爽：最终只保留结果，不刷屏

## 技术可行性评估

### 需要的飞书 API 能力

| 能力 | API 端点 | 必须性 | 验证状态 |
|------|---------|--------|---------|
| 消息表情（emoji reaction） | `POST /im/v1/messages/{message_id}/reactions` | ✅ 必须 | ⚠️ 待验证 |
| 移除表情 | `DELETE /im/v1/messages/{message_id}/reactions/{reaction_id}` | ✅ 必须 | ⚠️ 待验证 |
| 发送卡片消息 | `POST /im/v1/messages?msg_type=interactive` | ✅ 必须 | ⚠️ 待验证 |
| 更新卡片内容 | `PATCH /im/v1/messages/{message_id}` | ✅ 必须 | ⚠️ 待验证 |
| 订阅消息已读事件 | `im.message.message_read_v1` | ⚠️ 可选 | ⚠️ 待验证 |

**关键验证点**：
1. ✅ 当前已有能力：`im.message.receive_v1` 接收、文本消息发送
2. ❓ **消息表情 API**：需确认飞书 SDK 是否支持，是否需要额外权限
3. ❓ **卡片消息更新**：需确认是否支持原地更新（不是删除重发）
4. ❓ **消息已读事件**：当前只订阅了 `receive`，是否需要加 `message_read`（或在 `receive` 时立即响应）

### 技术架构设计

#### 方案对比

| 方案 | 触发点 | 优点 | 缺点 |
|------|--------|------|------|
| **A. 基于 message_read** | 用户读消息触发 | 精确（真的读了才加表情） | 需额外订阅事件；用户可能不读就等回复 |
| **B. 基于 message_receive** | 收到消息立即响应 | 简单；符合"收到就在处理"的语义 | 用户未读时也有表情（但合理） |

**推荐方案 B**（理由：message_receive 已有，立即反馈更好）

#### 实现架构

```
用户消息
  ↓
FeishuEventNormalizer.normalize()  [现有]
  ↓
InboundMessageService.onMessage()  [现有]
  ↓
[NEW] FeishuStreamListener 创建并注入
  ├─ 立即：addReaction(⌨️) + 发送初始卡片
  ├─ 流式：onToken() → 更新卡片（思考过程）
  ├─ 流式：onToolStart/End() → 更新卡片（工具状态）
  └─ 完成：removeReaction() + 更新卡片（最终结果）
  ↓
FeishuMessageSender [改造]
  ├─ sendCard() - 发送交互式卡片
  ├─ updateCard() - 更新卡片内容
  ├─ addReaction() - 添加表情
  └─ removeReaction() - 移除表情
```

### 涉及的代码改动

#### 新增文件

```
oryxos-channel-feishu/src/main/java/io/oryxos/channel/feishu/
  ├─ FeishuStreamListener.java        [NEW] - 流式监听器
  ├─ FeishuCardBuilder.java           [NEW] - 卡片 JSON 构建器
  └─ FeishuReactionManager.java       [NEW] - 表情管理器
```

#### 修改文件

```
oryxos-channel-feishu/src/main/java/io/oryxos/channel/feishu/
  ├─ FeishuMessageSender.java         [改] - 加 card/reaction 方法
  └─ FeishuChannelAdapter.java        [改] - 注入 StreamListener

oryxos-core/src/main/java/io/oryxos/core/channel/
  └─ InboundMessageService.java       [改] - 支持渠道级 listener 注入
```

### 卡片设计（Markdown 草案）

#### 处理中状态
```json
{
  "config": { "wide_screen_mode": true },
  "header": {
    "template": "blue",
    "title": { "tag": "plain_text", "content": "🤔 正在思考..." }
  },
  "elements": [
    {
      "tag": "markdown",
      "content": "**思考过程：**\n正在分析你的问题..."
    },
    {
      "tag": "markdown",
      "content": "**工具调用：**\n🔧 正在执行：read_file"
    }
  ]
}
```

#### 完成状态
```json
{
  "config": { "wide_screen_mode": true },
  "header": {
    "template": "green",
    "title": { "tag": "plain_text", "content": "✅ 回答" }
  },
  "elements": [
    {
      "tag": "markdown",
      "content": "这是 Agent 的最终回答内容..."
    }
  ]
}
```

## 实施计划

### Phase 1: API 验证（1-2 小时）
- [ ] 验证飞书表情 API（权限 + SDK 方法）
- [ ] 验证卡片发送与更新 API
- [ ] 确认 oapi-sdk 版本是否支持（当前 pom.xml 引用的版本）

### Phase 2: 核心实现（4-6 小时）
- [ ] `FeishuStreamListener` - 流式监听器核心逻辑
- [ ] `FeishuCardBuilder` - 卡片 JSON 构建
- [ ] `FeishuReactionManager` - 表情添加/移除
- [ ] `FeishuMessageSender` 扩展方法

### Phase 3: 集成与测试（2-3 小时）
- [ ] `InboundMessageService` 改造（支持渠道级 listener）
- [ ] 单元测试：卡片构建、表情管理
- [ ] 集成测试：完整流程
- [ ] 真机测试：飞书实际对话

### Phase 4: 文档与 PR（1-2 小时）
- [ ] 更新 `docs/FeishuChannelSetup.md`（权限要求）
- [ ] 新增 `specs/017-feishu-im-channel/streaming-card.md`（特性说明）
- [ ] PR 说明文档（changelog、演示 GIF）
- [ ] 提交 PR

**总时间估算**：8-13 小时（1-2 天）

## 潜在挑战与风险

### 技术风险

| 风险 | 可能性 | 影响 | 缓解方案 |
|------|--------|------|---------|
| 飞书 API 不支持消息更新 | 中 | 高 | 降级为"删除旧卡片+发新卡片"（replyTo 链式） |
| 表情 API 需额外权限 | 中 | 中 | 文档说明；或用文字占位符替代 |
| 卡片更新频率被限流 | 高 | 中 | 累积策略（2秒或100字更新一次） |
| oapi-sdk 版本过旧 | 低 | 高 | 升级 SDK（需回归测试） |

### 设计风险

| 风险 | 可能性 | 影响 | 缓解方案 |
|------|--------|------|---------|
| 卡片内容过长截断 | 高 | 低 | 思考过程只保留最近 N 条 |
| 多人同时 @ 群机器人 | 高 | 低 | 当前群聊已是无状态，影响不大 |
| 表情表达不够清晰 | 低 | 低 | 配合卡片标题，语义清楚 |

## 开源 PR 质量标准

### 必须包含
- [x] 清晰的 feature 说明（what & why）
- [ ] 代码遵循项目规范（CLAUDE.md + 宪法）
- [ ] 完整的单元测试
- [ ] 更新相关文档
- [ ] 演示 GIF 或视频（体验是核心卖点）
- [ ] Changelog 条目

### 加分项
- [ ] 支持配置开关（可选启用流式卡片）
- [ ] 降级方案（API 不可用时回退到文本）
- [ ] 性能数据（卡片更新延迟）
- [ ] 与 019 流式能力的架构一致性说明

## 对齐 OryxOS 宪法

检查点：
- ✅ **宪法 I**：自实现 ReAct（不变，只是输出方式改变）
- ✅ **宪法 II**：Spring AI 仅做协议转换（不变）
- ✅ **宪法 VI**：沙箱白名单（open.feishu.cn 已在白名单）
- ✅ **宪法 VII**：同步执行模型（StreamListener 同步回调）
- ✅ **模块边界**：改动仅在 `oryxos-channel-feishu`，核心最小改动

## 下一步行动

### 立即验证（30分钟）
```bash
# 1. 查看飞书 SDK 文档
# 2. 测试表情 API
curl -X POST https://open.feishu.cn/open-apis/im/v1/messages/{message_id}/reactions \
  -H "Authorization: Bearer $TOKEN" \
  -d '{"reaction_type": {"emoji_type": "KEYBOARD"}}'

# 3. 测试卡片更新 API
curl -X PATCH https://open.feishu.cn/open-apis/im/v1/messages/{message_id} \
  -H "Authorization: Bearer $TOKEN" \
  -d '{"content": "..."}'
```

### 获取社区反馈
- 在 OryxOS 仓库提 Issue 说明提案，征求维护者意见
- 确认是否与 roadmap 冲突
- 讨论设计细节（卡片样式、更新策略）

## 总结

### 这个 PR 的优点
1. ✅ **用户价值明确**：体验提升显著（从"干等"到"过程可见"）
2. ✅ **技术方案优雅**：利用飞书原生能力，不刷屏
3. ✅ **影响面可控**：只在飞书渠道内，风险低
4. ✅ **展示深度理解**：对飞书 API 和 OryxOS 架构的双重理解
5. ✅ **开源价值高**：可作为其他 IM 渠道（企微/钉钉）的参考

### 需要确认的点
1. ❓ 飞书 API 能力（表情/卡片更新）
2. ❓ OryxOS 维护者的接受度（架构方向）
3. ❓ 是否与现有 roadmap 冲突

### 建议
**这是一个优秀的 PR 提案！** 建议按以下顺序推进：
1. **先验证 API**（避免做无用功）
2. **提 Issue 征求意见**（避免方向偏离）
3. **实现 MVP**（先做最小可用版本）
4. **迭代优化**（根据反馈完善）

**我可以帮你做什么？**
- 帮你验证飞书 API
- 实现核心代码
- 编写测试
- 准备 PR 文档
