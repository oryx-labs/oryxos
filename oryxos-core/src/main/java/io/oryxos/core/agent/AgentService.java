package io.oryxos.core.agent;

import io.oryxos.core.profile.Profile;
import io.oryxos.core.profile.ProfileRegistry;
import io.oryxos.core.session.Session;
import io.oryxos.core.session.SessionManager;

/**
 * 一次处理的编排者：三种触发源（CLI / Web / 定时）最终都调同一个 {@link #process}。
 *
 * <p>ProfileContext 生命周期在此收口：入口 set、出口 finally clear——即使循环中途抛异常也必须清， 否则复用线程的下一个请求会拿到别人的
 * Profile（单请求测试永远测不出的串号 bug）。
 */
public class AgentService {

  private final ProfileRegistry profileRegistry;
  private final ReActLoop reActLoop;
  private final SessionManager sessionManager;

  public AgentService(
      ProfileRegistry profileRegistry, ReActLoop reActLoop, SessionManager sessionManager) {
    this.profileRegistry = profileRegistry;
    this.reActLoop = reActLoop;
    this.sessionManager = sessionManager;
  }

  public String process(Session session, String userMessage) {
    Profile profile =
        profileRegistry
            .get(session.profileName())
            .orElseThrow(
                () ->
                    new IllegalStateException("Session 引用的 Profile 不存在: " + session.profileName()));
    ProfileContext.set(profile); // 工具执行时靠它知道"当前是哪个 Agent"
    try {
      String reply = reActLoop.run(session, userMessage, profile);
      sessionManager.save(session); // 把累积完的历史持久化（仅正常路径）
      return reply;
    } finally {
      ProfileContext.clear(); // 虚拟线程每请求独立，用完必须清
    }
  }
}
