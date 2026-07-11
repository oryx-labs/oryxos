package io.oryxos.tool.notify;

/**
 * 出站通知抽象：把一条内容送到某个通知目标（入站有 Channel，这是对称的另一半）。
 *
 * <p>接口先行（TechSol §6.8）：签名不携带任何一档实现特有的词——核心阶段只挂通用 webhook，
 * 以后加企业微信/飞书专用渠道只新增实现类，不改接口、不改调用方。失败以异常表达，不许静默吞掉。
 */
public interface NotifyChannelAdapter {

  void send(NotifyTarget target, String content);
}
