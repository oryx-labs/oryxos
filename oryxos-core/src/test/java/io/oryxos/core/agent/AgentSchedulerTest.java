package io.oryxos.core.agent;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.oryxos.core.profile.Profile;
import io.oryxos.core.profile.Profile.ScheduleConfig;
import io.oryxos.core.profile.ProfileRegistry;
import io.oryxos.core.session.Session;
import io.oryxos.core.session.SessionManager;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.locks.Lock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.Trigger;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.scheduling.support.SimpleTriggerContext;

/**
 * 课件《第25节》验收 harness：AgentSchedulerTest——一个类覆盖四个坑。诀窍是**别真等时间**：{@code runOnce}
 * 是独立方法，直接调它就能测全部行为逻辑；cron 触发本身是 Spring 的事，只验"注册参数传对了"。
 */
class AgentSchedulerTest {

  private static final String PROFILE_NAME = "ops-agent";
  private static final String CRON = "0 0 9 * * *";
  private static final String ZONE = "Asia/Shanghai";

  private TaskScheduler taskScheduler;
  private ProfileRegistry profileRegistry;
  private AgentService agentService;
  private SessionManager sessionManager;
  private ScheduledTaskStore taskStore;
  private AgentScheduler scheduler;

  @BeforeEach
  void setUp() {
    taskScheduler = mock(TaskScheduler.class);
    agentService = mock(AgentService.class);
    sessionManager = mock(SessionManager.class);
    profileRegistry = mock(ProfileRegistry.class);
    taskStore = mock(ScheduledTaskStore.class);
    // 28 节：默认所有任务启用，否则 runOnce 会因停用而跳过（Mockito boolean 默认 false）
    when(taskStore.isEnabled(any())).thenReturn(true);
    scheduler =
        new AgentScheduler(taskScheduler, profileRegistry, agentService, sessionManager, taskStore);
  }

  private static ScheduleConfig sc(String id) {
    return new ScheduleConfig(id, CRON, ZONE, "汇总昨天的 PR 进度");
  }

  private static Profile profileWith(List<ScheduleConfig> schedules) {
    return profileNamed(PROFILE_NAME, schedules.toArray(new ScheduleConfig[0]));
  }

  private static Profile profileNamed(String name, ScheduleConfig... schedules) {
    return new Profile(
        name,
        null,
        null,
        new Profile.ProviderRef("deepseek", "deepseek-chat", null),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(schedules),
        List.of(),
        Profile.Settings.defaults());
  }

  @Test
  @DisplayName("注册时CronTrigger带上配置的cron和时区")
  void registerPassesCronAndZoneToTrigger() {
    when(profileRegistry.all()).thenReturn(List.of(profileWith(List.of(sc("daily")))));

    scheduler.registerAll();

    ArgumentCaptor<Trigger> captor = ArgumentCaptor.forClass(Trigger.class);
    verify(taskScheduler).schedule(any(Runnable.class), captor.capture());
    CronTrigger trigger = assertInstanceOf(CronTrigger.class, captor.getValue());
    assertEquals(CRON, trigger.getExpression(), "cron 表达式必须来自配置");
    // 时区行为回归：CronTrigger.equals 只比 cron 不比时区，故用固定 TriggerContext 下的"下一次触发时刻"证明时区生效——
    // 与"同 cron + 配置时区"参照一致、与别的时区不一致，证明时区没被服务器默认顶掉（坑四）。
    SimpleTriggerContext ctx =
        new SimpleTriggerContext(Instant.EPOCH, Instant.EPOCH, Instant.EPOCH);
    assertEquals(
        new CronTrigger(CRON, ZoneId.of(ZONE)).nextExecution(ctx),
        trigger.nextExecution(ctx),
        "时区必须来自配置");
    assertNotEquals(
        new CronTrigger(CRON, ZoneId.of("America/New_York")).nextExecution(ctx),
        trigger.nextExecution(ctx),
        "时区不能被服务器默认顶掉");
  }

  @Test
  @DisplayName("上一次还没跑完_本次触发直接跳过")
  void previousRunStillActive_currentTriggerSkipped() throws InterruptedException {
    Profile profile = profileWith(List.of(sc("task-1")));
    Lock lock = scheduler.lockFor("task-1");
    // 真实重叠是跨线程的（调度线程池）：ReentrantLock 对同线程可重入，必须让另一线程占着锁，
    // runOnce 的 tryLock 才会真失败。用闩确保占锁完成后再触发、断言完再放锁。
    CountDownLatch locked = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    Thread holder =
        new Thread(
            () -> {
              lock.lock();
              locked.countDown();
              try {
                release.await();
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              } finally {
                lock.unlock();
              }
            });
    holder.start();
    locked.await(); // 确保另一线程已占锁，模拟"上一次还在跑"

    try {
      scheduler.runOnce(profile, sc("task-1"));
      verify(agentService, never()).process(any(), any()); // 没有叠加执行
    } finally {
      release.countDown();
      holder.join();
    }
  }

  @Test
  @DisplayName("任务抛异常_不外抛且锁必须被释放")
  void taskThrows_notRethrown_lockReleased() {
    Profile profile = profileWith(List.of(sc("task-1")));
    when(sessionManager.getOrCreate(any(), any(), any()))
        .thenReturn(new Session("sched-sid", PROFILE_NAME));
    when(agentService.process(any(), any())).thenThrow(new RuntimeException("boom"));

    assertDoesNotThrow(() -> scheduler.runOnce(profile, sc("task-1"))); // 调度器不死

    scheduler.runOnce(profile, sc("task-1")); // 二进宫：再触发一次
    // 能进来第二次——锁真的在 finally 放了，没有永久卡死
    verify(agentService, times(2)).process(any(), any());
  }

  @Test
  @DisplayName("会话三元组固定_两次触发拿同一Session")
  void sessionIdentityFixed_reusesSameSession() {
    Profile profile = profileWith(List.of(sc("task-1")));
    Session shared = new Session("sched-sid", PROFILE_NAME);
    when(sessionManager.getOrCreate("scheduler", "scheduler", PROFILE_NAME)).thenReturn(shared);

    scheduler.runOnce(profile, sc("task-1"));
    scheduler.runOnce(profile, sc("task-1"));

    // 三元组固定 scheduler/scheduler/profileName
    verify(sessionManager, times(2)).getOrCreate("scheduler", "scheduler", PROFILE_NAME);
    // 两次都拿到同一个 Session（复用、历史累积）
    ArgumentCaptor<Session> sessionCaptor = ArgumentCaptor.forClass(Session.class);
    verify(agentService, times(2)).process(sessionCaptor.capture(), eq("汇总昨天的 PR 进度"));
    assertSame(shared, sessionCaptor.getAllValues().get(0));
    assertSame(shared, sessionCaptor.getAllValues().get(1));
  }

  @Test
  @DisplayName("多Agent并存_各自的钟推用各自的Session互不串号")
  void multipleAgents_eachSchedulerRunUsesItsOwnSession() {
    Profile alpha = profileNamed("alpha-agent", sc("alpha-task"));
    Profile beta = profileNamed("beta-agent", sc("beta-task"));
    Session alphaSession = new Session("alpha-sid", "alpha-agent");
    Session betaSession = new Session("beta-sid", "beta-agent");
    when(sessionManager.getOrCreate("scheduler", "scheduler", "alpha-agent"))
        .thenReturn(alphaSession);
    when(sessionManager.getOrCreate("scheduler", "scheduler", "beta-agent"))
        .thenReturn(betaSession);

    scheduler.runOnce(alpha, sc("alpha-task"));
    scheduler.runOnce(beta, sc("beta-task"));

    // 两个 Agent 的钟推各自按 profileName 拿到不同 Session——三元组只有 profile 段不同，互不串号
    ArgumentCaptor<Session> sessionCaptor = ArgumentCaptor.forClass(Session.class);
    verify(agentService, times(2)).process(sessionCaptor.capture(), eq("汇总昨天的 PR 进度"));
    assertSame(alphaSession, sessionCaptor.getAllValues().get(0));
    assertSame(betaSession, sessionCaptor.getAllValues().get(1));
    assertNotEquals(
        alphaSession.sessionId(), betaSession.sessionId(), "不同 Agent 的钟推 Session 必须互相独立");
  }

  @Test
  @DisplayName("单条非法cron不拖垮其它规则的注册")
  void invalidCronSkipped_othersStillRegistered() {
    ScheduleConfig bad = new ScheduleConfig("bad", "not-a-cron", ZONE, "x");
    ScheduleConfig good = sc("good");
    when(profileRegistry.all()).thenReturn(List.of(profileWith(List.of(bad, good))));

    assertDoesNotThrow(() -> scheduler.registerAll());

    // 坏的被跳过、好的照常注册（只注册成功 1 条）
    verify(taskScheduler, times(1)).schedule(any(Runnable.class), any(Trigger.class));
  }

  @Test
  @DisplayName("无任何定时规则_注册空跑不报错")
  void noSchedules_registerAllIsNoop() {
    when(profileRegistry.all()).thenReturn(List.of(profileWith(List.of())));

    assertDoesNotThrow(() -> scheduler.registerAll());

    verify(taskScheduler, never()).schedule(any(Runnable.class), any(Trigger.class));
  }
}
