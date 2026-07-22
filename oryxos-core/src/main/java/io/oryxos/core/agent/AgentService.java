package io.oryxos.core.agent;

import io.oryxos.core.memory.MemoryScope;
import io.oryxos.core.memory.MemoryService;
import io.oryxos.core.profile.Profile;
import io.oryxos.core.profile.ProfileRegistry;
import io.oryxos.core.session.Session;
import io.oryxos.core.session.SessionManager;
import io.oryxos.core.skill.AgentSkillCoordinator;
import io.oryxos.core.skill.SkillLease;
import io.oryxos.core.skill.SkillSnapshot;

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
  private final AgentSkillCoordinator skillCoordinator;

  public AgentService(
      ProfileRegistry profileRegistry, ReActLoop reActLoop, SessionManager sessionManager) {
    this(profileRegistry, reActLoop, sessionManager, null, null);
  }

  public AgentService(
      ProfileRegistry profileRegistry,
      ReActLoop reActLoop,
      SessionManager sessionManager,
      MemoryService memoryService) {
    this(profileRegistry, reActLoop, sessionManager, memoryService, null);
  }

  public AgentService(
      ProfileRegistry profileRegistry,
      ReActLoop reActLoop,
      SessionManager sessionManager,
      AgentSkillCoordinator skillCoordinator) {
    this(profileRegistry, reActLoop, sessionManager, null, skillCoordinator);
  }

  /** 生产运行时构造器：同时保留触发记忆，并经 Skill 协调器冻结快照、持有读租约。 */
  public AgentService(
      ProfileRegistry profileRegistry,
      ReActLoop reActLoop,
      SessionManager sessionManager,
      MemoryService memoryService,
      AgentSkillCoordinator skillCoordinator) {
    this.profileRegistry = profileRegistry;
    this.reActLoop = reActLoop;
    this.sessionManager = sessionManager;
    this.memoryService = memoryService;
    this.skillCoordinator = skillCoordinator;
  }

  public String process(Session session, String userMessage) {
    if (skillCoordinator == null) {
      return processCompatibility(session, userMessage);
    }
    try {
      try (SkillLease lease = skillCoordinator.openRequest(session.profileName())) {
        Profile profile = resolveProfile(session);
        ProfileContext.set(profile); // 工具执行时靠它知道"当前是哪个 Agent"
        String reply = reActLoop.run(session, userMessage, profile, lease.snapshot());
        sessionManager.save(session); // 读租约覆盖正常路径的会话持久化
        recordTriggerIfConfigured(profile.name(), userMessage, reply);
        return reply;
      }
    } finally {
      // 外层 finally 同时覆盖 openRequest、Profile 查询、循环和 save 的全部异常路径。
      ProfileContext.clear();
    }
  }

  /** 仅供旧调用/旧测试兼容；生产装配必须使用带 coordinator 的构造器。 */
  private String processCompatibility(Session session, String userMessage) {
    SkillSnapshot skills = SkillSnapshot.empty(session.profileName());
    try {
      Profile profile = resolveProfile(session);
      ProfileContext.set(profile);
      String reply = reActLoop.run(session, userMessage, profile, skills);
      sessionManager.save(session);
      recordTriggerIfConfigured(profile.name(), userMessage, reply);
      return reply;
    } finally {
      ProfileContext.clear();
    }
  }

  private Profile resolveProfile(Session session) {
    return profileRegistry
        .get(session.profileName())
        .orElseThrow(
            () -> new IllegalStateException("Session 引用的 Profile 不存在: " + session.profileName()));
  }

  private void recordTriggerIfConfigured(String agentName, String userMessage, String reply) {
    if (memoryService != null) {
      recordTrigger(agentName, userMessage, reply);
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
