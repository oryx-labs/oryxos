# 飞书流式卡片 PR 提交前检查清单

## 代码完成度

### 核心功能
- [x] FeishuCardBuilder - 卡片构建器
  - [x] buildInitialCard() - 初始卡片
  - [x] buildProcessingCard() - 处理中卡片
  - [x] buildCompletedCard() - 完成卡片
  - [x] buildErrorCard() - 错误卡片
  
- [x] FeishuReactionManager - 表情管理器
  - [x] addKeyboardReaction() - 添加表情
  - [x] removeReaction() - 移除表情
  
- [x] FeishuStreamListener - 流式监听器
  - [x] start() - 启动流程
  - [x] onTextDelta() - 文本增量
  - [x] onToolStart() - 工具开始
  - [x] onToolEnd() - 工具结束
  - [x] finish() - 完成流程
  
- [x] FeishuMessageSender 扩展
  - [x] sendCard() - 发送卡片
  - [x] updateCard() - 更新卡片
  
- [x] 集成到 InboundMessageService
  - [x] 创建流式监听器
  - [x] 启动监听器
  - [x] 传入 agentService.process()
  - [x] 完成时调用 finish()

### 接口定义
- [x] InboundChannelAdapter.createStreamListener() - 接口方法
- [x] FeishuChannelAdapter.createStreamListener() - 实现

## 代码质量

- [x] 编译通过
- [x] 代码格式化（spotless:apply）
- [ ] 单元测试通过（需要 Maven 安装完成）
- [ ] Checkstyle 检查通过
- [x] 符合项目命名规范
- [x] 符合 OryxOS 宪法原则
- [x] 注释完整（类、方法、关键逻辑）
- [x] 异常处理完善（降级方案）

## 测试验证

### 单元测试
- [x] FeishuStreamListenerTest 已编写
- [ ] 测试覆盖率 > 80%
- [ ] 所有测试用例通过

### 集成测试（需要真实环境）
- [ ] 表情添加成功
- [ ] 表情移除成功
- [ ] 初始卡片发送成功
- [ ] 卡片实时更新成功
- [ ] 最终卡片更新成功
- [ ] 错误卡片展示正确
- [ ] 降级到文本回复正常
- [ ] 群聊和私聊都正常工作

### 性能测试
- [ ] 卡片更新不会导致限流
- [ ] 多用户并发正常
- [ ] 长文本处理正常

## 文档完善

- [x] 功能说明文档（feishu-streaming-card.md）
- [ ] CHANGELOG.md 更新
- [ ] README.md 更新（如需要）
- [ ] API 文档更新（如需要）
- [x] 代码内注释完整
- [ ] 演示视频/截图准备

## 兼容性检查

- [x] 不破坏现有功能
- [x] 向后兼容（非流式渠道不受影响）
- [x] 降级方案完善（API 失败不影响核心）
- [ ] 飞书 SDK 版本兼容性确认

## 权限和安全

- [x] 飞书 API 域名在沙箱白名单
- [x] 不泄露敏感信息
- [x] 错误信息脱敏（sanitize）
- [ ] 权限要求文档化

## PR 准备

### PR 内容
- [ ] 清晰的标题（feat(channel-feishu): ...）
- [ ] 详细的描述（参考模板）
- [ ] 关联 Issue（如有）
- [ ] 标签（enhancement, feishu, streaming）

### PR 质量
- [ ] 提交历史清晰（squash 冗余提交）
- [ ] 提交信息规范（Conventional Commits）
- [ ] 分支从最新 main 创建
- [ ] 无合并冲突

### PR 材料
- [ ] 演示视频（核心卖点）
- [ ] 对比截图（有/无流式）
- [ ] 性能数据（可选）

## 沟通准备

- [ ] 在 Issue 中提前沟通过想法
- [ ] 获得维护者初步认可
- [ ] 准备好回答技术问题
- [ ] 准备好根据反馈修改

## 当前状态

### ✅ 已完成
1. 核心代码实现（4 个新文件 + 4 个修改文件）
2. 编译通过
3. 代码格式化
4. 功能文档完整
5. 单元测试编写
6. 符合 OryxOS 规范

### ⏳ 待完成
1. Maven 安装完成（后台运行中）
2. 单元测试验证通过
3. 真实飞书环境测试
4. 演示视频录制
5. CHANGELOG 更新
6. 提交 Issue 征求意见
7. 整理提交历史
8. 提交 PR

### 🔴 阻塞项
- Maven 后台安装任务（等待完成）

## 下一步行动

1. **等待 Maven 安装完成**
   ```bash
   # 检查后台任务状态
   jobs
   # 或查看输出
   cat /private/tmp/claude-501/.../tasks/bv1iak9jh.output
   ```

2. **运行单元测试**
   ```bash
   mvn test -pl oryxos-channel-feishu -Dtest=FeishuStreamListenerTest
   ```

3. **运行完整测试套件**
   ```bash
   mvn test -pl oryxos-channel-feishu
   ```

4. **真实环境测试准备**
   - 配置飞书应用
   - 配置 Agent 绑定飞书渠道
   - 启动 OryxOS gateway
   - 测试并录制演示视频

5. **提交前准备**
   ```bash
   # 检查代码风格
   mvn spotless:check
   mvn checkstyle:check
   
   # 运行所有测试
   mvn test
   
   # 创建功能分支
   git checkout -b feat/feishu-streaming-card
   
   # 提交代码
   git add .
   git commit -m "feat(channel-feishu): 实现流式卡片回复

- 新增 FeishuCardBuilder 卡片构建器
- 新增 FeishuReactionManager 表情管理器
- 新增 FeishuStreamListener 流式监听器
- 扩展 FeishuMessageSender 支持卡片发送和更新
- 集成到 InboundMessageService 流式处理流程

用户体验：收到消息立即反馈表情，实时展示思考过程和工具调用，
完成后只保留最终结果。类似 OpenClaw 的处理方式。

Co-authored-by: [你的名字] <your.email@example.com>"
   
   # 推送分支
   git push origin feat/feishu-streaming-card
   ```

6. **提交 PR**
   - 在 GitHub 上创建 PR
   - 使用准备好的 PR 模板
   - 上传演示视频
   - 等待 code review

## 注意事项

1. **API 限流风险**
   - 当前实现在工具调用时立即更新
   - 如遇限流，考虑加入更长的累积间隔

2. **飞书 SDK 版本**
   - 当前使用 2.8.5
   - 确认表情和卡片 API 在该版本可用

3. **测试环境**
   - 需要真实飞书应用
   - 需要配置好权限
   - 建议先在测试群测试

4. **代码审查准备**
   - 可能被要求调整更新策略
   - 可能被要求添加配置开关
   - 可能被要求增强错误处理

## 预计时间

- Maven 安装：等待后台任务（已在运行）
- 单元测试验证：5 分钟
- 真实环境准备：30 分钟（配置飞书应用）
- 演示录制：15 分钟
- 文档完善：15 分钟
- 提交准备：15 分钟

**总计：~1.5 小时**（不含 Maven 安装时间）
