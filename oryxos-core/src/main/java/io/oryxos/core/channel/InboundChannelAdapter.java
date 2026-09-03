package io.oryxos.core.channel;

import java.util.Optional;

/**
 * 入站 IM 渠道适配器契约：一实例 = 一个平台应用的一条接入（017 FR-010）。
 *
 * <p>适配器只做「平台协议 ↔ 归一化模型」转换与连接生命周期管理；去重、路由、会话分流、错误回复文案、 审计等契约语义统一收敛在 {@link
 * InboundMessageService}，适配器不得复制这些逻辑。新增 IM 渠道 = 实现本接口 + 在适配器工厂注册类型，核心模块零修改（SC-007 由测试桩钉死）。
 *
 * <p>实现必须保持纯 POJO（禁 Spring 依赖），由 {@code OryxOsRuntime} 显式装配。
 */
public interface InboundChannelAdapter {

  /** channels.yaml 条目名。 */
  String name();

  /** 渠道类型（如 "feishu"）。 */
  String type();

  /** 本渠道绑定的 Agent 名（一应用一 Agent，Clarify-Q2）；编排服务据此路由。 */
  String boundAgent();

  /** 建立长连接并开始接收事件。前置校验（凭证已解析、绑定 Agent 存在）失败时必须抛出带点名原因的异常，不得静默（FR-013/SC-008）。 */
  void start();

  /** 幂等断开连接；未启动时调用无副作用。 */
  void stop();

  /** 实时连接状态。 */
  ChannelStatus status();

  /**
   * 发送回复到来源处（FR-007）。实现负责平台上限分段（FR-009）与出站沙箱校验（宪法 VI）。
   *
   * @param chatId 回复目标（私聊/群）
   * @param text 回复正文
   * @param replyToMessageId 非空时引用原消息（群聊必传，使回答与提问可对应）；私聊传 null 直发
   */
  void sendReply(String chatId, String text, String replyToMessageId);

  /**
   * 可选：打开进度流（如飞书交互卡片实时更新）。默认 empty，编排走整段 {@link #sendReply}。
   *
   * @param chatId 回复目标会话
   * @param replyToMessageId 群聊引用原消息 id；私聊 null
   */
  default Optional<InboundProgressStream> openProgressStream(
      String chatId, String replyToMessageId) {
    return Optional.empty();
  }
}
