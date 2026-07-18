package io.oryxos.core.agent;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.oryxos.core.profile.Profile;
import io.oryxos.core.profile.Profile.ScheduleConfig;
import io.oryxos.core.profile.ProfileRegistry;
import io.oryxos.core.profile.ProfileValidationException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/** 课件《第30节》验收 harness：AgentLifecycleServiceTest——编排顺序 + 失败回滚 + 删除时序。 */
class AgentLifecycleServiceTest {

  private static final String MD =
      "---\nname: demo\nprovider:\n  name: deepseek\n  model: m\n---\n正文";

  private AgentLoader agentLoader;
  private ProfileRegistry profileRegistry;
  private AgentScheduler agentScheduler;
  private AgentStore agentStore;
  private AgentLifecycleService service;

  @BeforeEach
  void setUp() {
    agentLoader = mock(AgentLoader.class);
    profileRegistry = mock(ProfileRegistry.class);
    agentScheduler = mock(AgentScheduler.class);
    agentStore = mock(AgentStore.class);
    service =
        new AgentLifecycleService(
            agentLoader,
            profileRegistry,
            agentScheduler,
            agentStore,
            mock(io.oryxos.core.provider.ProviderService.class),
            "deepseek",
            "deepseek",
            "deepseek-chat");
  }

  private static Profile profile(String name, ScheduleConfig... schedules) {
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
  @DisplayName("create 按序：脚手架写目录 → 派生 → 注册")
  void create_scaffoldsThenRegisters() throws Exception {
    Path dir = Path.of("agents", "demo");
    when(profileRegistry.exists("demo")).thenReturn(false);
    when(agentStore.writeAll(eq("demo"), any())).thenReturn(dir);
    Profile p = profile("demo");
    doReturn(p).when(agentLoader).deriveProfile(dir);

    assertSame(p, service.create("demo", "一个测试 Agent"));

    InOrder o = inOrder(agentStore, profileRegistry);
    o.verify(agentStore).writeAll(eq("demo"), any()); // 后台按模板脚手架出完整目录
    o.verify(profileRegistry).register(p);
    verify(agentScheduler, never()).registerProfile(any()); // 无 schedules
  }

  @Test
  @DisplayName("name 冲突第一步就拒、一个目录都不写")
  void create_nameConflict_rejectedBeforeAnyWrite() {
    when(profileRegistry.exists("demo")).thenReturn(true);

    assertThrows(IllegalArgumentException.class, () -> service.create("demo", "x"));

    verify(agentStore, never()).writeAll(any(), any());
  }

  @Test
  @DisplayName("注册失败_回滚已写的Agent目录_不留半个Agent")
  void create_registerFails_rollsBackWrittenDir() throws Exception {
    Path dir = Path.of("agents", "half");
    when(profileRegistry.exists("half")).thenReturn(false);
    when(agentStore.writeAll(eq("half"), any())).thenReturn(dir);
    doReturn(profile("half")).when(agentLoader).deriveProfile(dir);
    doThrow(new ProfileValidationException("bad")).when(profileRegistry).register(any());

    assertThrows(ProfileValidationException.class, () -> service.create("half", "x"));

    verify(agentStore).delete(dir); // 已写目录被删回去
    verify(agentScheduler, never()).registerProfile(any()); // 定时根本没走到
  }

  @Test
  @DisplayName("register 带 schedules 的 Agent 注册定时（三录入同一段代码）")
  void register_withSchedules_registersTimer() throws Exception {
    Path dir = Path.of("agents", "cron");
    Profile p = profile("cron", new ScheduleConfig("m", "0 0 9 * * *", "Asia/Shanghai", "x"));
    doReturn(p).when(agentLoader).deriveProfile(dir);

    service.register(dir);

    verify(profileRegistry).register(p);
    verify(agentScheduler).registerProfile(p);
  }

  @Test
  @DisplayName("删除必须先停定时_再动索引和目录")
  void delete_unregistersThenRemovesThenArchives() {
    Profile p =
        profile("weather-daily", new ScheduleConfig("m", "0 0 9 * * *", "Asia/Shanghai", "x"));
    when(profileRegistry.get("weather-daily")).thenReturn(Optional.of(p));

    service.delete("weather-daily");

    InOrder o = inOrder(agentScheduler, profileRegistry, agentStore);
    o.verify(agentScheduler).unregisterProfile(p); // 顺序反了：定时还在跑、Profile 已没 → 触发空指针
    o.verify(profileRegistry).remove("weather-daily");
    o.verify(agentStore).archive("weather-daily");
  }

  @Test
  @DisplayName("update 改 schedules 先注销旧再注册新")
  void update_scheduleChanged_unregistersBeforeRegister() throws Exception {
    Profile old = profile("w", new ScheduleConfig("m", "0 0 9 * * *", "Asia/Shanghai", "旧"));
    Profile updated = profile("w", new ScheduleConfig("m", "0 0 10 * * *", "Asia/Shanghai", "新"));
    when(profileRegistry.get("w")).thenReturn(Optional.of(old));
    Path dir = Path.of("agents", "w");
    when(agentStore.write(eq("w"), any())).thenReturn(dir);
    doReturn(updated).when(agentLoader).deriveProfile(dir);

    service.update("w", MD);

    InOrder o = inOrder(agentScheduler);
    o.verify(agentScheduler).unregisterProfile(old); // 先注销旧句柄
    o.verify(agentScheduler).registerProfile(updated); // 再注册新的
  }
}
