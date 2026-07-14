package io.oryxos.core.agent;

import io.oryxos.core.profile.Profile;
import io.oryxos.core.profile.Profile.ScheduleConfig;
import io.oryxos.core.profile.ProfileRegistry;
import io.oryxos.core.session.Session;
import io.oryxos.core.session.SessionManager;
import java.time.ZoneId;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.support.CronTrigger;

/**
 * 定时任务这个第三触发源（"钟推"）。CLI/Web 是人推，本类到点自己拼一条消息交给 {@link AgentService#process}——
 * 走跟人推完全一样的入口，ReAct/Tool/Provider 一个字不用改（§8.5）。
 *
 * <p>只干一件事：到点了拼消息、交给编排入口。消息说什么是 Profile/SKILL.md 的事，交上去怎么处理是 ReActLoop 的事， 都不归本类管——职责划窄，不膨胀成工作流引擎。
 *
 * <p>四个坑的解法：坑一配置驱动（{@code TaskScheduler.schedule(...)} 动态注册，不用编译期写死的 {@code @Scheduled}）；
 * 坑二重叠跳过（按任务 id 的进程内 {@link ReentrantLock} + {@code tryLock}，核心阶段单实例，非分布式锁）； 坑三失败隔离（单次失败只记日志不外抛、审计走
 * {@code process} 既有链路、{@code finally} 必放锁）； 坑四时区显式（{@link CronTrigger} 带 {@link
 * ZoneId}，不由服务器系统时区替用户做主）。
 *
 * <p>纯 POJO：装配与启动注册由 {@code OryxOsRuntime} 显式做（{@code @Bean(initMethod="registerAll")}）， 不在 core
 * 类里放 Spring 注解（与 AgentService/ReActLoop 同构）。
 */
public class AgentScheduler {

  private static final Logger LOG = LoggerFactory.getLogger(AgentScheduler.class);

  /** 钟推的会话身份三元组固定值：同一 Profile 的历次定时触发复用同一 Session（§8.5、FR-006）。 */
  private static final String SCHEDULER_CHANNEL = "scheduler";

  private static final String SCHEDULER_USER = "scheduler";

  private final TaskScheduler taskScheduler;
  private final ProfileRegistry profileRegistry;
  private final AgentService agentService;
  private final SessionManager sessionManager;

  /** 任务 id → 锁：防同一任务重叠执行。进程内锁，核心阶段单实例足够（非分布式锁）。 */
  private final ConcurrentMap<String, Lock> taskLocks = new ConcurrentHashMap<>();

  public AgentScheduler(
      TaskScheduler taskScheduler,
      ProfileRegistry profileRegistry,
      AgentService agentService,
      SessionManager sessionManager) {
    this.taskScheduler = taskScheduler;
    this.profileRegistry = profileRegistry;
    this.agentService = agentService;
    this.sessionManager = sessionManager;
  }

  /** 启动时扫一遍所有 Profile 的 schedules，逐条注册进 TaskScheduler（配置驱动，坑一）。 */
  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "CRLF_INJECTION_LOGS",
      justification = "日志里的 id/cron/zone 来自运营方手写的 Profile YAML 配置（非请求输入），仅用于启动诊断")
  public void registerAll() {
    for (Profile profile : profileRegistry.all()) {
      for (ScheduleConfig sc : profile.schedules()) {
        try {
          CronTrigger trigger = new CronTrigger(sc.cron(), resolveZone(sc.zone()));
          taskScheduler.schedule(() -> runOnce(profile, sc), trigger);
          LOG.info("已注册定时任务 {}（cron={} zone={}）", sc.id(), sc.cron(), sc.zone());
        } catch (RuntimeException e) {
          // 坑四附带：单条 cron/时区非法只跳过这条，不拖垮其它规则的注册（FR-007）
          LOG.warn("定时任务 {} 配置非法，跳过：{}", sc.id(), e.getMessage());
        }
      }
    }
  }

  /**
   * 一次触发从头到尾：拿锁 → 拼消息交给编排入口 → 失败记日志 → finally 放锁。拿不到锁说明上一次还没跑完，直接跳过。 public 为可测（harness
   * 直接调、抓锁），不是对外 API。
   */
  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "CRLF_INJECTION_LOGS",
      justification = "日志里的 sc.id() 来自运营方手写的 Profile YAML 配置（非请求输入），仅用于诊断")
  public void runOnce(Profile profile, ScheduleConfig sc) {
    Lock lock = lockFor(sc.id());
    if (!lock.tryLock()) {
      LOG.info("定时任务 {} 上一次还在跑，跳过本次触发", sc.id()); // 坑二：不排队、不并行两份
      return;
    }
    try {
      // channel/user 固定为 scheduler：同一 Profile 历次触发复用同一 Session，历史靠 max_history_turns 截断兜底。
      // session_id 仍由 SessionManager 内部按三元组拼（本类不碰 session_id 生成）。
      Session session =
          sessionManager.getOrCreate(SCHEDULER_CHANNEL, SCHEDULER_USER, profile.name());
      agentService.process(session, sc.message()); // 走跟 CLI/Web 完全一样的入口；审计在 process 内部
    } catch (Exception e) {
      // 坑三：一次失败只记日志、不外抛，不把调度器搞挂、不影响其它任务的下次触发
      LOG.error("定时任务 {} 执行失败", sc.id(), e);
    } finally {
      lock.unlock(); // 成功失败都必须放锁，否则这个任务永远卡住
    }
  }

  /** 按任务 id 取同一把锁。public 为可测（harness 占锁模拟"上一次还在跑"）。 */
  public Lock lockFor(String taskId) {
    return taskLocks.computeIfAbsent(taskId, id -> new ReentrantLock());
  }

  /** 时区显式（坑四）：空/blank 时才退回服务器系统时区，否则按配置解析。 */
  private ZoneId resolveZone(String zone) {
    return zone == null || zone.isBlank() ? ZoneId.systemDefault() : ZoneId.of(zone);
  }
}
