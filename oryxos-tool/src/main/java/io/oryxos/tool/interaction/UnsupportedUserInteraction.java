package io.oryxos.tool.interaction;

/** 无人值守渠道（定时 / Web 无状态）的交互实现：直接报不支持，绝不静默卡住等一个永远不来的回答。 */
public class UnsupportedUserInteraction implements UserInteraction {

  @Override
  public String ask(String question) {
    throw new InteractionUnavailableException("当前渠道不支持向用户提问（无交互终端）");
  }
}
