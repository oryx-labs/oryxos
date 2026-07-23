package io.oryxos.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.oryxos.core.profile.Profile;
import io.oryxos.core.profile.Profile.ScheduleConfig;
import io.oryxos.core.profile.ProfileRegistry;
import io.oryxos.core.profile.ProfileValidationException;
import io.oryxos.core.skill.AgentSkillCoordinator;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
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
    when(profileRegistry.existsIdentity("demo")).thenReturn(false);
    when(agentStore.createAll(eq("demo"), any())).thenReturn(dir);
    Profile p = profile("demo");
    doReturn(p).when(agentLoader).deriveProfile(dir);

    assertSame(p, service.create("demo", "一个测试 Agent"));

    InOrder o = inOrder(agentStore, profileRegistry);
    o.verify(agentStore).createAll(eq("demo"), any()); // 后台按模板脚手架出完整目录
    o.verify(profileRegistry).register(p);
    verify(agentScheduler, never()).registerProfile(any()); // 无 schedules

    @SuppressWarnings("unchecked")
    ArgumentCaptor<Map<String, String>> files = ArgumentCaptor.forClass(Map.class);
    verify(agentStore).createAll(eq("demo"), files.capture());
    assertTrue(files.getValue().containsKey("skills/example/SKILL.md"));
    assertTrue(files.getValue().get("skills/example/SKILL.md").contains("name: example"));
    assertTrue(files.getValue().get("skills/example/SKILL.md").contains("description:"));
  }

  @Test
  @DisplayName("name 冲突第一步就拒、一个目录都不写")
  void create_nameConflict_rejectedBeforeAnyWrite() {
    when(profileRegistry.existsIdentity("demo")).thenReturn(true);

    assertThrows(IllegalArgumentException.class, () -> service.create("demo", "x"));

    verify(agentStore, never()).createAll(any(), any());
  }

  @Test
  @DisplayName("create 在落盘前拒绝已注册 Agent 的大小写别名")
  void create_caseAliasConflict_rejectedBeforeAnyWrite() {
    when(profileRegistry.existsIdentity("ops")).thenReturn(true);

    assertThrows(IllegalArgumentException.class, () -> service.create("ops", "x"));

    verify(agentStore, never()).createAll(any(), any());
    verify(agentStore, never()).delete(any());
  }

  @Test
  @DisplayName("注册失败_回滚已写的Agent目录_不留半个Agent")
  void create_registerFails_rollsBackWrittenDir() throws Exception {
    Path dir = Path.of("agents", "half");
    when(profileRegistry.existsIdentity("half")).thenReturn(false);
    when(agentStore.createAll(eq("half"), any())).thenReturn(dir);
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
  @DisplayName("删除先原子归档，成功后再停定时并移出索引")
  void delete_archivesThenUnregistersAndRemoves() {
    Profile p =
        profile("weather-daily", new ScheduleConfig("m", "0 0 9 * * *", "Asia/Shanghai", "x"));
    when(profileRegistry.get("weather-daily")).thenReturn(Optional.of(p));

    service.delete("weather-daily");

    InOrder o = inOrder(agentScheduler, profileRegistry, agentStore);
    o.verify(agentStore).archive("weather-daily");
    o.verify(agentScheduler).unregisterProfile(p);
    o.verify(profileRegistry).remove("weather-daily");
  }

  @Test
  @DisplayName("归档原子移动失败时 Profile 和定时注册保持不变")
  void delete_archiveFailure_preservesRuntimeState() {
    Profile p = profile("weather-daily");
    when(profileRegistry.get("weather-daily")).thenReturn(Optional.of(p));
    doThrow(new java.io.UncheckedIOException(new java.io.IOException("atomic move failed")))
        .when(agentStore)
        .archive("weather-daily");

    assertThrows(java.io.UncheckedIOException.class, () -> service.delete("weather-daily"));

    verify(agentScheduler, never()).unregisterProfile(any());
    verify(profileRegistry, never()).remove(any());
  }

  @Test
  @DisplayName("update 改 schedules 先注销旧再注册新")
  void update_scheduleChanged_unregistersBeforeRegister() throws Exception {
    Profile old = profile("w", new ScheduleConfig("m", "0 0 9 * * *", "Asia/Shanghai", "旧"));
    Profile updated = profile("w", new ScheduleConfig("m", "0 0 10 * * *", "Asia/Shanghai", "新"));
    when(profileRegistry.get("w")).thenReturn(Optional.of(old));
    Path dir = Path.of("agents", "w");
    when(agentStore.write(eq("w"), any())).thenReturn(dir);
    doReturn(updated).when(agentLoader).parse(MD, "w");

    service.update("w", MD);

    InOrder o = inOrder(agentScheduler);
    o.verify(agentScheduler).unregisterProfile(old); // 先注销旧句柄
    o.verify(agentScheduler).registerProfile(updated); // 再注册新的
  }

  @Test
  @DisplayName("update 先校验 AGENT.md，非法内容不覆盖旧文件")
  void update_invalidMarkdown_doesNotWrite() {
    doThrow(new ProfileValidationException("bad")).when(agentLoader).parse("invalid", "demo");

    assertThrows(ProfileValidationException.class, () -> service.update("demo", "invalid"));

    verify(agentStore, never()).write(any(), any());
    verify(agentScheduler, never()).unregisterProfile(any());
  }

  @Test
  @DisplayName("saveFiles 复用落盘前已解析的 Profile，不在提交后二次读盘")
  void saveFiles_reusesValidatedProfileWithoutSecondRead() throws Exception {
    Profile updated = profile("demo");
    doReturn(updated).when(agentLoader).parse(MD, "demo");
    when(agentStore.writeAll(eq("demo"), any())).thenReturn(Path.of("agents", "demo"));

    assertSame(updated, service.saveFiles("demo", Map.of("AGENT.md", MD)));

    verify(agentLoader, never()).deriveProfile(any());
    verify(profileRegistry).register(updated);
  }

  @Test
  @DisplayName("saveFiles 拒绝与已校验 AGENT.md 等价的大小写别名且保留运行状态")
  void saveFiles_caseAliasCannotReplaceValidatedAgentMarkdown(@TempDir Path root)
      throws IOException {
    AgentStore realStore = new AgentStore(root);
    Path agentDir = realStore.write("demo", "old");
    Profile updated = profile("demo");
    doReturn(updated).when(agentLoader).parse(MD, "demo");
    when(profileRegistry.get("demo")).thenReturn(Optional.of(profile("demo")));
    AgentLifecycleService realStoreService =
        new AgentLifecycleService(
            agentLoader,
            profileRegistry,
            agentScheduler,
            realStore,
            mock(io.oryxos.core.provider.ProviderService.class),
            "deepseek",
            "deepseek",
            "deepseek-chat");
    Map<String, String> files = new LinkedHashMap<>();
    files.put("AGENT.md", MD);
    files.put("agent.md", "tampered");

    assertThrows(IllegalArgumentException.class, () -> realStoreService.saveFiles("demo", files));

    assertEquals("old", Files.readString(agentDir.resolve("AGENT.md")));
    verify(profileRegistry, never()).register(any());
    verify(agentScheduler, never()).unregisterProfile(any());
  }

  @Test
  @DisplayName("saveFiles 在协调器、解析和落盘前拒绝 Skill 控制 sidecar")
  void saveFiles_reservedSkillSidecar_rejectedBeforeMutation() {
    AgentSkillCoordinator coordinator = mock(AgentSkillCoordinator.class);
    AgentLifecycleService coordinated =
        new AgentLifecycleService(
            agentLoader,
            profileRegistry,
            agentScheduler,
            agentStore,
            mock(io.oryxos.core.provider.ProviderService.class),
            "deepseek",
            "deepseek",
            "deepseek-chat",
            coordinator,
            null);
    Map<String, String> files = new LinkedHashMap<>();
    files.put("AGENT.md", MD);
    files.put("skills/weather/.oryxos-origin.yml", "forged");

    assertThrows(IllegalArgumentException.class, () -> coordinated.saveFiles("demo", files));

    verifyNoInteractions(coordinator, agentLoader, agentStore, agentScheduler, profileRegistry);
  }

  @Test
  @DisplayName("Workspace Skill 文件入口在协调器和落盘前拒绝保留成员")
  void writeManagedSkillFile_reservedMember_rejectedBeforeMutation() {
    AgentSkillCoordinator coordinator = mock(AgentSkillCoordinator.class);
    AgentLifecycleService coordinated =
        new AgentLifecycleService(
            agentLoader,
            profileRegistry,
            agentScheduler,
            agentStore,
            mock(io.oryxos.core.provider.ProviderService.class),
            "deepseek",
            "deepseek",
            "deepseek-chat",
            coordinator,
            null);

    assertThrows(
        IllegalArgumentException.class,
        () ->
            coordinated.writeManagedSkillFile(
                "demo", "skills/weather/references/.oryxos-control", "forged"));

    verifyNoInteractions(coordinator, agentStore);
  }

  @Test
  @DisplayName("create/update/saveFiles/delete 统一路由到同一个 Agent 写协调器")
  void lifecycleMutationsUseSharedSkillCoordinator() {
    AgentSkillCoordinator coordinator = mock(AgentSkillCoordinator.class);
    AgentLifecycleService coordinated =
        new AgentLifecycleService(
            agentLoader,
            profileRegistry,
            agentScheduler,
            agentStore,
            mock(io.oryxos.core.provider.ProviderService.class),
            "deepseek",
            "deepseek",
            "deepseek-chat",
            coordinator,
            null);

    coordinated.create("demo", "x");
    coordinated.update("demo", MD);
    coordinated.saveFiles("demo", Map.of("AGENT.md", MD));
    coordinated.delete("demo");

    verify(coordinator, org.mockito.Mockito.times(4)).mutate(eq("demo"), any());
  }
}
