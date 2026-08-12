package io.oryxos.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import io.oryxos.core.notify.NotifyChannelRegistry;
import io.oryxos.core.profile.ProfileRegistry;
import io.oryxos.core.provider.ProviderResponse;
import io.oryxos.core.provider.ProviderService;
import io.oryxos.core.skill.AgentSkillBindingService;
import io.oryxos.core.skill.InstalledSkillCatalog;
import io.oryxos.core.skill.SkillCatalog;
import io.oryxos.core.skill.SkillLoader;
import io.oryxos.core.skill.SkillRegistry;
import io.oryxos.core.skill.SkillStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentLifecycleSkillBindingTest {

  @TempDir Path root;

  private ProfileRegistry profiles;
  private SkillRegistry skillRegistry;
  private AgentSkillBindingService bindings;
  private ProviderService provider;

  @BeforeEach
  void setUp() throws Exception {
    Files.createDirectories(root.resolve("agents"));
    SkillStore skillStore = new SkillStore(root);
    SkillLoader skillLoader = new SkillLoader(root.resolve("skills"));
    skillRegistry = new SkillRegistry();
    io.oryxos.core.skill.SkillService skillService =
        new io.oryxos.core.skill.SkillService(skillStore, skillRegistry, skillLoader);
    skillService.create("required", "必选", "required body");
    skillService.create("suggested", "建议", "suggested body");
    profiles = new ProfileRegistry();
    bindings = new AgentSkillBindingService(root, skillLoader);
    provider = mock(ProviderService.class);
  }

  @Test
  void directCreateAndGeneratedSidecarNeverPersistLegacySkills() throws Exception {
    AgentLifecycleService service = service(bindings, new InstalledSkillCatalog(skillRegistry));
    service.create("direct", "直接创建", List.of("required"));
    assertTrue(Files.isSymbolicLink(root.resolve("agents/direct/skills/required")));
    assertFalse(Files.readString(root.resolve("agents/direct/AGENT.md")).contains("skills:"));

    when(provider.chat(eq("agent-author-draft"), any(), any()))
        .thenReturn(
            new ProviderResponse(
                """
                ---
                name: draft
                description: 草稿
                identity:
                  agent_name: 草稿
                  prompt: 按职责执行
                provider:
                  name: mock
                  model: mock
                tools: []
                settings:
                  max_iterations: 10
                  max_history_turns: 20
                skills:
                  - suggested
                ---
                生成报告
                """,
                List.of(),
                null));

    GeneratedAgentDraft draft = service.generateDraft("draft", "生成报告", null, List.of("required"));

    assertEquals(List.of("required"), draft.requiredSkills());
    assertEquals(List.of("suggested"), draft.suggestedSkills());
    assertEquals(List.of("required", "suggested"), draft.bindingSkills());
    assertFalse(draft.files().get("AGENT.md").contains("skills:"));
    assertFalse(Files.exists(root.resolve("agents/draft")), "草稿 sidecar 不落盘");
  }

  @Test
  void catalogRejectsUninstalledAndCreateRollsBackBindingFailure() {
    SkillCatalog uninstalled =
        (q, visibility) ->
            List.of(
                new io.oryxos.core.skill.SkillCatalogEntry(
                    "required",
                    "必选",
                    io.oryxos.core.skill.SkillCatalogEntry.Visibility.PUBLIC,
                    "external",
                    false));
    assertThrows(
        IllegalArgumentException.class,
        () -> service(bindings, uninstalled).create("blocked", "x", List.of("required")));
    assertFalse(Files.exists(root.resolve("agents/blocked")));

    AgentSkillBindingService failing = mock(AgentSkillBindingService.class);
    when(failing.replaceBindings(eq("rollback"), any()))
        .thenThrow(new IllegalStateException("link failed"));
    assertThrows(
        IllegalStateException.class,
        () ->
            service(failing, new InstalledSkillCatalog(skillRegistry))
                .create("rollback", "x", List.of("required")));
    assertFalse(Files.exists(root.resolve("agents/rollback")));
    assertFalse(profiles.exists("rollback"));
  }

  @Test
  void generatedUnknownSuggestionAndReservedSkillFilesAreRejected() {
    AgentLifecycleService service = service(bindings, new InstalledSkillCatalog(skillRegistry));
    when(provider.chat(eq("agent-author-bad"), any(), any()))
        .thenReturn(
            new ProviderResponse(
                "---\nname: bad\nprovider:\n  name: mock\n  model: mock\nskills:\n  - unknown\n---\nbody",
                List.of(),
                null));
    assertThrows(
        IllegalArgumentException.class, () -> service.generateDraft("bad", "x", null, List.of()));

    when(provider.chat(eq("agent-author-files"), any(), any()))
        .thenReturn(
            new ProviderResponse(
                "===FILE: AGENT.md===\n---\nname: files\nprovider:\n  name: mock\n  model: mock\n---\nbody\n"
                    + "===FILE: skills/copy.md===\ncopy",
                List.of(),
                null));
    assertThrows(
        IllegalArgumentException.class, () -> service.generateDraft("files", "x", null, List.of()));
  }

  @Test
  void existingSaveRestoresFilesAndBindingsWhenBindingCommitFails() throws Exception {
    AgentLifecycleService initial = service(bindings, new InstalledSkillCatalog(skillRegistry));
    initial.create("existing", "旧描述", List.of("required"));
    Path markdown = root.resolve("agents/existing/AGENT.md");
    String before = Files.readString(markdown);
    AgentSkillBindingService failing = spy(bindings);
    doThrow(new IllegalStateException("binding commit failed"))
        .when(failing)
        .replaceBindings("existing", List.of("suggested"));
    String replacement = before.replace("description: 旧描述", "description: 新描述");

    assertThrows(
        IllegalStateException.class,
        () ->
            service(failing, new InstalledSkillCatalog(skillRegistry))
                .saveFiles(
                    "existing",
                    Map.of("AGENT.md", replacement, "scripts/new.py", "new"),
                    List.of("suggested")));

    assertEquals(before, Files.readString(markdown));
    assertFalse(Files.exists(root.resolve("agents/existing/scripts/new.py")));
    assertEquals(
        List.of("required"),
        bindings.inspect("existing").bindings().stream()
            .map(io.oryxos.core.skill.BoundSkillDescriptor::name)
            .toList());
  }

  private AgentLifecycleService service(
      AgentSkillBindingService bindingService, SkillCatalog catalog) {
    AgentLoader loader = new AgentLoader(root.resolve("agents"), java.util.Set.of("mock"));
    return new AgentLifecycleService(
        loader,
        profiles,
        mock(AgentScheduler.class),
        new AgentStore(root),
        provider,
        "mock",
        "mock",
        "mock",
        Map.of(),
        mock(NotifyChannelRegistry.class),
        null,
        skillRegistry,
        bindingService,
        catalog);
  }
}
