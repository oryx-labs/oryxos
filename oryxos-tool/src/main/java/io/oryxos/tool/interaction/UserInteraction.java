package io.oryxos.tool.interaction;

/**
 * 向人提问的抽象（human-in-the-loop 的落点）：接口先行，不绑定具体渠道。
 *
 * <p>CLI 场景读终端（{@link ConsoleUserInteraction}）；Web / 定时等无人值守场景没有交互终端， 装配 {@link
 * UnsupportedUserInteraction}——ask_user 明确报"当前渠道不支持交互"而非静默卡住。
 */
public interface UserInteraction {

  /**
   * 向用户提问并拿回答。
   *
   * @throws InteractionUnavailableException 当前渠道无法与人交互时
   */
  String ask(String question);
}
