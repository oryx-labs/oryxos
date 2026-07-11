package io.oryxos.tool.builtin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.oryxos.core.ToolResult;
import io.oryxos.core.agent.ProfileContext;
import io.oryxos.core.profile.Profile;
import io.oryxos.tool.notify.NotifyChannelAdapter;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 课件《第19节》验收 harness：NotifyToolsTest——第一批可测集。
 *
 * <p>InOrder 白名单顺序回归（课件"发送前必须先过白名单校验"：enforce 先于 send）待 24 节 Sandbox 就位后补入本类——课件"实现顺序说明"明文分批。
 */
class NotifyToolsTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private NotifyChannelAdapter adapter;
  private NotifyTools notifyTools;

  @BeforeEach
  void setUp() {
    adapter = mock(NotifyChannelAdapter.class);
    // 同一 mock 挂两个 type：路由正确性由 send 收到的 NotifyTarget.channelType 断言
    notifyTools = new NotifyTools(Map.of("webhook", adapter, "feishu", adapter));
  }

  @AfterEach
  void clearContext() {
    ProfileContext.clear(); // ThreadLocal 必清——17 节同款纪律
  }

  private static Profile profileWith(List<Profile.NotifyChannel> channels) {
    return new Profile(
        "ops-agent",
        null,
        null,
        new Profile.ProviderRef("deepseek", "deepseek-chat", null),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        channels,
        List.of(),
        List.of(),
        Profile.Settings.defaults());
  }

  private static Profile twoChannelProfile() {
    return profileWith(
        List.of(
            new Profile.NotifyChannel("webhook", Map.of("url", "https://hooks.example.com/a")),
            new Profile.NotifyChannel("feishu", Map.of("url", "https://open.feishu.cn/b"))));
  }

  private ToolResult notify(String content, String channel) {
    var input = MAPPER.createObjectNode();
    if (content != null) {
      input.put("content", content);
    }
    if (channel != null) {
      input.put("channel", channel);
    }
    return notifyTools.execute(input);
  }

  @Test
  @DisplayName("notify_channels未配置_明确报错不静默失败")
  void unconfiguredChannelsFailsExplicitly() {
    ProfileContext.set(profileWith(List.of()));

    ToolResult result = notify("hello", null);

    assertFalse(result.success(), "不是静默失败——Agent 不会以为发出去了");
    assertTrue(result.errorMessage().contains("notify_channels"), "报错点名未配置项");
    verify(adapter, never()).send(any(), any());
  }

  @Test
  @DisplayName("channel参数缺省_取第一个渠道")
  void defaultChannelPicksFirstConfigured() {
    ProfileContext.set(twoChannelProfile());

    ToolResult result = notify("hello", null);

    assertTrue(result.success());
    assertEquals("已推送", result.content());
    verify(adapter).send(argThat(t -> "webhook".equals(t.channelType())), eq("hello")); // 第一个渠道
  }

  @Test
  @DisplayName("channel 传 \"default\" 等同缺省")
  void defaultLiteralBehavesLikeOmitted() {
    ProfileContext.set(twoChannelProfile());

    notify("hello", "default");

    verify(adapter).send(argThat(t -> "webhook".equals(t.channelType())), eq("hello"));
  }

  @Test
  @DisplayName("channel 指定类型_命中对应渠道")
  void explicitChannelTypeMatches() {
    ProfileContext.set(twoChannelProfile());

    notify("hello", "feishu");

    verify(adapter)
        .send(
            argThat(
                t ->
                    "feishu".equals(t.channelType())
                        && "https://open.feishu.cn/b".equals(t.config().get("url"))),
            eq("hello"));
  }

  @Test
  @DisplayName("指定类型不存在_报错点名不回退")
  void unknownChannelTypeFailsWithoutFallback() {
    ProfileContext.set(twoChannelProfile());

    ToolResult result = notify("hello", "dingtalk");

    assertFalse(result.success());
    assertTrue(result.errorMessage().contains("dingtalk"), "点名未命中的类型");
    verify(adapter, never()).send(any(), any()); // 不回退默认渠道——回退会把消息发错地方
  }

  @Test
  @DisplayName("当前无 Agent 上下文_报错不发送")
  void missingProfileContextFails() {
    ToolResult result = notify("hello", null);

    assertFalse(result.success());
    verify(adapter, never()).send(any(), any());
  }

  @Test
  @DisplayName("渠道类型没有对应实现_报错点名已装配清单")
  void channelTypeWithoutAdapterFails() {
    ProfileContext.set(
        profileWith(
            List.of(
                new Profile.NotifyChannel("dingtalk", Map.of("url", "https://oapi.example.com")))));

    ToolResult result = notify("hello", null);

    assertFalse(result.success());
    assertTrue(result.errorMessage().contains("dingtalk"), "点名缺实现的类型");
    verify(adapter, never()).send(any(), any());
  }

  @Test
  @DisplayName("content 缺失_报错不发送")
  void missingContentFails() {
    ProfileContext.set(twoChannelProfile());

    ToolResult result = notify(null, null);

    assertFalse(result.success());
    assertTrue(result.errorMessage().contains("content"));
    verify(adapter, never()).send(any(), any());
  }
}
