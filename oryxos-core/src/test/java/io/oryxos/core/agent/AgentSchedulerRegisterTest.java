package io.oryxos.core.agent;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import io.oryxos.core.profile.Profile;
import io.oryxos.core.profile.Profile.ScheduleConfig;
import io.oryxos.core.profile.ProfileRegistry;
import io.oryxos.core.session.SessionManager;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.Trigger;

/** 课件《第29节》验收 harness：AgentSchedulerRegisterTest——registerProfile 留句柄、定时来自 Agent。 */
class AgentSchedulerRegisterTest {

  private static final String CRON = "0 0 9 * * *";
  private static final String ZONE = "Asia/Shanghai";

  private TaskScheduler taskScheduler;
  private ScheduledTaskStore taskStore;
  private AgentScheduler scheduler;

  @BeforeEach
  void setUp() {
    taskScheduler = mock(TaskScheduler.class);
    taskStore = mock(ScheduledTaskStore.class);
    ScheduledFuture<?> future = mock(ScheduledFuture.class);
    // ScheduledFuture<?> 通配符：doReturn 避开 thenReturn 的类型捕获问题
    doReturn(future).when(taskScheduler).schedule(any(Runnable.class), any(Trigger.class));
    scheduler =
        new AgentScheduler(
            taskScheduler,
            mock(ProfileRegistry.class),
            mock(AgentService.class),
            mock(SessionManager.class),
            taskStore);
  }

  private static Profile profileWithSchedule(String name, ScheduleConfig sc) {
    return new Profile(
        name,
        null,
        null,
        new Profile.ProviderRef("deepseek", "deepseek-chat", null),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(sc),
        List.of(),
        Profile.Settings.defaults());
  }

  @Test
  @DisplayName("registerProfile 后 scheduledTasks 有可注销句柄")
  void registerProfile_leavesCancellableHandle() {
    scheduler.registerProfile(
        profileWithSchedule("ops", new ScheduleConfig("morning", CRON, ZONE, "到点了")));

    assertTrue(scheduler.hasScheduledTask("morning"), "登记带定时的 Agent 后留有可注销句柄");
    assertFalse(scheduler.hasScheduledTask("nonexistent"));
  }

  @Test
  @DisplayName("cron/时区来自 Profile.schedules，不来自别处")
  void cronAndZoneComeFromProfileSchedules() {
    scheduler.registerProfile(
        profileWithSchedule("ops", new ScheduleConfig("morning", CRON, ZONE, "到点了")));

    // taskStore.register 收到的 cron/zone 正是 Profile.schedules 里声明的那对
    verify(taskStore).register(eq("morning"), eq("ops"), eq(CRON), eq(ZONE), eq("到点了"), any());
  }
}
