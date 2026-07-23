package io.oryxos.core.agent;

import io.oryxos.core.memory.MemoryScope;
import io.oryxos.core.memory.MemoryService;
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
@edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "profileRegistry 是 Spring 注入的单例注册表，三种触发源共享同一引用正是意图（29 节起可运行时增删，必须同一份）。")
public class AgentService {

  private static final int MEMORY_LINE_MAX = 200;

  private final ProfileRegistry profileRegistry;
  private final ReActLoop reActLoop;
  private final SessionManager sessionManager;
  private final MemoryService memoryService;

  public AgentService(
      ProfileRegistry profileRegistry,
      ReActLoop reActLoop,
      SessionManager sessionManager,
      MemoryService memoryService) {
    this.profileRegistry = profileRegistry;
    this.reActLoop = reActLoop;
    this.sessionManager = sessionManager;
    this.memoryService = memoryService;
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
      recordTrigger(profile.name(), userMessage, reply); // 每次触发留一条归档记忆（运行足迹）
      return reply;
    } finally {
      ProfileContext.clear(); // 虚拟线程每请求独立，用完必须清
    }
  }

  /** 每次触发都往这个 Agent 的记忆归档区记一条运行足迹（这个 Agent 干过什么，事后可回看）。 */
  private void recordTrigger(String agentName, String userMessage, String reply) {
    String line = "触发「" + oneLine(userMessage) + "」⇒ " + oneLine(reply);
    // remember 靠 ToolExecutionContext 定位 Agent（同工具写记忆的路径）：读写路径外要自己置入再清除
    ToolExecutionContext.setAgentName(agentName);
    try {
      memoryService.remember(line, MemoryScope.ARCHIVAL);
    } finally {
      ToolExecutionContext.clear();
    }
  }

  /** 记忆是逐行存的：把多行压成一行、超长截断，避免撑坏归档区的行结构。 */
  private static String oneLine(String text) {
    if (text == null || text.isBlank()) {
      return "（空）";
    }
    String flat = text.replaceAll("\\s+", " ").strip();
    return flat.length() > MEMORY_LINE_MAX ? flat.substring(0, MEMORY_LINE_MAX) + "…" : flat;
  }
}
