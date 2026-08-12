package io.oryxos.core.agent;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import io.oryxos.core.OryxTool;
import io.oryxos.core.ToolResult;
import io.oryxos.core.context.ContextLoader;
import io.oryxos.core.profile.Profile;
import io.oryxos.core.provider.ProviderRequest;
import io.oryxos.core.provider.ProviderResponse;
import io.oryxos.core.provider.ProviderService;
import io.oryxos.core.provider.ToolCallRequest;
import io.oryxos.core.session.Session;
import io.oryxos.core.skill.AgentSkillBindingService;
import io.oryxos.core.skill.SkillMetadataReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReActLoopSkillDisclosureTest {

  @TempDir Path root;

  @Test
  void readFileIsAuditedAndNextRoundRebuildsMetadataWithoutPreloadingBody() throws IOException {
    Path agent = Files.createDirectories(root.resolve("agents/reporter"));
    Files.writeString(agent.resolve("AGENT.md"), "---\nname: reporter\n---\n按职责执行");
    Path skill = Files.createDirectories(root.resolve("skills/report-format"));
    Path skillFile = skill.resolve("SKILL.md");
    Files.writeString(skillFile, "---\nname: report-format\ndescription: 旧描述\n---\n只在读取后出现的正文");
    Path links = Files.createDirectories(agent.resolve("skills"));
    Files.createSymbolicLink(
        links.resolve("report-format"), Path.of("../../../skills/report-format"));

    OryxTool readFile =
        new OryxTool() {
          @Override
          public String getName() {
            return "read_file";
          }

          @Override
          public String getDescription() {
            return "读取文件";
          }

          @Override
          public String getInputSchema() {
            return "{}";
          }

          @Override
          public ToolResult execute(JsonNode input) {
            try {
              return ToolResult.ok(Files.readString(Path.of(input.path("path").asText())));
            } catch (IOException e) {
              return ToolResult.error(e.getMessage(), false);
            }
          }
        };
    AgentSkillBindingService bindings =
        new AgentSkillBindingService(root, new SkillMetadataReader());
    PromptBuilder prompts =
        new PromptBuilder(
            new ContextLoader(root, bindings),
            Map.of("read_file", readFile),
            Clock.fixed(Instant.parse("2026-07-27T00:00:00Z"), ZoneOffset.UTC));
    ProviderService provider = mock(ProviderService.class);
    ToolInvocationAuditor auditor = mock(ToolInvocationAuditor.class);
    ToolExecutor executor = new ToolExecutor(Map.of("read_file", readFile), auditor);
    List<ProviderRequest> requests = new ArrayList<>();
    ToolCallRequest call =
        new ToolCallRequest(
            "read_file", "{\"path\":\"" + links.resolve("report-format/SKILL.md") + "\"}");
    when(provider.chat(eq("s-1"), any(), any()))
        .thenAnswer(
            invocation -> {
              requests.add(invocation.getArgument(2));
              Files.writeString(
                  skillFile, "---\nname: report-format\ndescription: 新描述\n---\n只在读取后出现的正文");
              return new ProviderResponse(null, List.of(call), null);
            })
        .thenAnswer(
            invocation -> {
              requests.add(invocation.getArgument(2));
              return new ProviderResponse("完成", List.of(), null);
            });

    ReActLoop loop = new ReActLoop(prompts, provider, executor);
    loop.run(new Session("s-1", "reporter"), "生成报告", profile());

    assertTrue(requests.get(0).systemPrompt().contains("旧描述"));
    assertFalse(requests.get(0).systemPrompt().contains("只在读取后出现的正文"));
    assertTrue(requests.get(1).systemPrompt().contains("新描述"));
    assertTrue(
        requests.get(1).messages().stream()
            .anyMatch(message -> message.content().contains("只在读取后出现的正文")));
    verify(auditor).record(eq("s-1"), eq("read_file"), any(), any(), eq(true), eq(null), anyLong());
  }

  private static Profile profile() {
    return new Profile(
        "reporter",
        "报告助手",
        null,
        new Profile.ProviderRef("mock", "mock", null),
        List.of("read_file"),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        new Profile.Settings(2, 20));
  }
}
