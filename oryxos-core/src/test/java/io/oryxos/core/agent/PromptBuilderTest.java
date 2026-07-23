package io.oryxos.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.oryxos.core.OryxTool;
import io.oryxos.core.context.ContextLoader;
import io.oryxos.core.profile.Profile;
import io.oryxos.core.provider.ProviderRequest;
import io.oryxos.core.session.Session;
import io.oryxos.core.skill.SkillSnapshot;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 课件《第17节》验收 harness：PromptBuilderTest。 */
class PromptBuilderTest {

  private static final Clock FIXED_CLOCK =
      Clock.fixed(Instant.parse("2026-07-10T08:30:00Z"), ZoneId.of("Asia/Shanghai"));

  private ContextLoader contextLoader;
  private OryxTool httpGetTool;
  private PromptBuilder builder;

  @BeforeEach
  void setUp() {
    contextLoader = mock(ContextLoader.class);
    when(contextLoader.load(any(), any())).thenReturn("system-context");
    httpGetTool = mock(OryxTool.class);
    when(httpGetTool.getName()).thenReturn("http_get");
    builder = new PromptBuilder(contextLoader, Map.of("http_get", httpGetTool), FIXED_CLOCK);
  }

  private Profile profile(int maxHistoryTurns, List<String> tools) {
    return new Profile(
        "ops-agent",
        null,
        new Profile.Identity("运维小欧", "你是运维助手"),
        new Profile.ProviderRef("deepseek", "deepseek-chat", null),
        tools,
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        new Profile.Settings(10, maxHistoryTurns));
  }

  private Session sessionWithTurns(int turns) {
    Session session = new Session("s-1", "ops-agent");
    for (int i = 1; i <= turns; i++) {
      session.appendUser("问题" + i);
    }
    return session;
  }

  @Test
  @DisplayName("system 段进 systemPrompt、历史进结构化 messages、工具走 availableTools")
  void fourPartsAssembledInFixedOrder() {
    Session session = sessionWithTurns(1);

    ProviderRequest request = builder.build(session, profile(20, List.of("http_get")));

    assertTrue(request.systemPrompt().contains("system-context"), "system 段进 systemPrompt");
    assertTrue(hasMsg(request, "问题1"), "历史进结构化 messages（不再拍平进文本）");
    assertEquals(List.of(httpGetTool), request.availableTools(), "工具列表经 availableTools 传递");
  }

  @Test
  @DisplayName("历史超 N 轮被截断（坑二回归）")
  void historyBeyondMaxTurnsIsTruncated() {
    Session session = sessionWithTurns(25);

    ProviderRequest request = builder.build(session, profile(20, List.of()));

    assertFalse(hasMsg(request, "问题5"), "最早的轮次被截掉");
    assertTrue(hasMsg(request, "问题6"), "保留最近 20 轮的第一轮");
    assertTrue(hasMsg(request, "问题25"), "最新一轮必在");
  }

  @Test
  @DisplayName("恰好 N 轮不截断")
  void historyExactlyAtLimitIsKept() {
    Session session = sessionWithTurns(20);

    ProviderRequest request = builder.build(session, profile(20, List.of()));

    assertTrue(hasMsg(request, "问题1"), "恰好 N 轮时一条不丢");
  }

  @Test
  @DisplayName("system prompt 末尾含当前日期时间")
  void systemPartEndsWithCurrentDateTime() {
    ProviderRequest request = builder.build(sessionWithTurns(1), profile(20, List.of()));

    // Clock.fixed: 2026-07-10T08:30Z = 北京时间 16:30——模型自己不知道今天几号，全靠这一行
    assertTrue(request.systemPrompt().contains("2026-07-10 16:30"), "缺日期时间行，定时场景的'今天'就没了");
  }

  @Test
  @DisplayName("availableTools 只含 Profile 点名的工具")
  void availableToolsFilteredByProfile() {
    ProviderRequest request = builder.build(sessionWithTurns(1), profile(20, List.of()));

    assertTrue(request.availableTools().isEmpty(), "Profile 没点名任何工具就不带");
  }

  @Test
  @DisplayName("一轮=一条用户消息及其后全部消息（截断以轮为界不撕裂）")
  void truncationCutsAtTurnBoundaryKeepingToolMessages() {
    Session session = new Session("s-1", "ops-agent");
    for (int i = 1; i <= 3; i++) {
      session.appendUser("问题" + i);
      session.appendToolResult(
          new io.oryxos.core.provider.ToolCallRequest("http_get", "{}"),
          io.oryxos.core.ToolResult.ok("结果" + i));
    }

    ProviderRequest request = builder.build(session, profile(2, List.of()));

    assertFalse(hasMsg(request, "问题1"), "第 1 轮整体被截");
    assertFalse(hasMsg(request, "结果1"), "轮内工具消息随轮整体截掉，不撕裂");
    assertTrue(hasMsg(request, "问题2") && hasMsg(request, "结果2"));
    assertTrue(hasMsg(request, "问题3") && hasMsg(request, "结果3"));
  }

  /** 结构化历史里是否有一条消息的 content 含 sub（替代旧的 request.content() 文本包含判断）。 */
  private static boolean hasMsg(ProviderRequest request, String sub) {
    return request.messages().stream()
        .anyMatch(m -> m.content() != null && m.content().contains(sub));
  }

  @Test
  @DisplayName("显式 SkillSnapshot 原样透传给 ContextLoader，不在 Builder 内重扫")
  void explicitSkillSnapshotIsPassedThroughUnchanged() {
    Profile profile = profile(20, List.of());
    SkillSnapshot snapshot = SkillSnapshot.empty(profile.name());

    builder.build(sessionWithTurns(1), profile, snapshot);

    verify(contextLoader).load(same(profile), same(snapshot));
  }
}
