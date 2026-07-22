package io.oryxos.tool.builtin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.oryxos.core.ToolResult;
import io.oryxos.core.agent.ProfileContext;
import io.oryxos.core.notify.NotifyChannelDef;
import io.oryxos.core.notify.NotifyChannelRegistry;
import io.oryxos.core.profile.Profile;
import io.oryxos.tool.notify.NotifyChannelAdapter;
import io.oryxos.tool.sandbox.FileSandboxProperties;
import io.oryxos.tool.sandbox.HttpSandboxProperties;
import io.oryxos.tool.sandbox.PermissiveSandbox;
import io.oryxos.tool.sandbox.SandboxViolationException;
import io.oryxos.tool.sandbox.ShellSandboxProperties;
import io.oryxos.tool.sandbox.WhitelistSandbox;
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
    // 同一 mock 挂两个 type：路由正确性由 send 收到的 NotifyTarget.channelType 断言。
    // 既有用例的推送地址（hooks.example.com/open.feishu.cn）与域名白名单无关——用 Permissive 隔离沙箱变量，
    // 让本组仍只测渠道路由/报错语义；沙箱拦截单列 pushToDomainOutsideWhitelist_adapterNeverSends 用真 WhitelistSandbox。
    notifyTools =
        new NotifyTools(
            Map.of("webhook", adapter, "feishu", adapter),
            new PermissiveSandbox(),
            emptyRegistry());
  }

  /** 空注册表 mock：本组用例只测「按 type 匹配 Agent 内联渠道」的兼容路径，故按名解析一律 miss。 */
  private static NotifyChannelRegistry emptyRegistry() {
    NotifyChannelRegistry registry = mock(NotifyChannelRegistry.class);
    when(registry.find(any())).thenReturn(java.util.Optional.empty());
    when(registry.list()).thenReturn(java.util.List.of());
    return registry;
  }

  @AfterEach
  void clearContext() {
    ProfileContext.clear(); // ThreadLocal 必清——17 节同款纪律
  }

  @Test
  @DisplayName("channel 传注册表里的渠道名_按名解析成 type+url 发送（31 节新模型，不依赖 AGENT.md 内联）")
  void channelNameResolvesFromRegistry() {
    NotifyChannelRegistry registry = mock(NotifyChannelRegistry.class);
    when(registry.find("team-lark"))
        .thenReturn(
            java.util.Optional.of(
                new NotifyChannelDef(
                    "team-lark", "feishu", "https://open.feishu.cn/hook/x", "团队群")));
    NotifyTools tools =
        new NotifyTools(Map.of("feishu", adapter), new PermissiveSandbox(), registry);
    // 关键：不设置 ProfileContext 的内联 notify_channels——新模型完全靠注册表按名解析
    var input = MAPPER.createObjectNode();
    input.put("content", "今日北京天气 + 穿搭建议");
    input.put("channel", "team-lark");

    ToolResult result = tools.execute(input);

    assertTrue(result.success(), "按渠道名解析成功即发送");
    assertEquals("已推送", result.content());
    verify(adapter)
        .send(
            argThat(
                t ->
                    "feishu".equals(t.channelType())
                        && "https://open.feishu.cn/hook/x".equals(t.config().get("url"))),
            eq("今日北京天气 + 穿搭建议"));
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

  @Test
  @DisplayName("白名单外域名推送_adapter从不发送")
  void pushToDomainOutsideWhitelist_adapterNeverSends() {
    // 真 WhitelistSandbox（只允许 *.example.com），推送目标 hooks.evil.com——校验先于 send，
    // enforce 抛 SandboxViolationException 上抛不吞（走 ToolExecutor 失败审计），adapter.send 从未被调用
    NotifyTools guarded =
        new NotifyTools(
            Map.of("webhook", adapter),
            new WhitelistSandbox(
                new FileSandboxProperties(List.of()),
                new ShellSandboxProperties(List.of()),
                new HttpSandboxProperties(List.of("*.example.com"))),
            emptyRegistry());
    ProfileContext.set(
        profileWith(
            List.of(
                new Profile.NotifyChannel("webhook", Map.of("url", "https://hooks.evil.com/x")))));
    var input = MAPPER.createObjectNode();
    input.put("content", "hello");

    assertThrows(SandboxViolationException.class, () -> guarded.execute(input));
    verify(adapter, never()).send(any(), any());
  }

  @Test
  @DisplayName("白名单内域名推送_校验放行正常发送")
  void pushToDomainInsideWhitelist_sendsNormally() {
    NotifyTools guarded =
        new NotifyTools(
            Map.of("webhook", adapter),
            new WhitelistSandbox(
                new FileSandboxProperties(List.of()),
                new ShellSandboxProperties(List.of()),
                new HttpSandboxProperties(List.of("*.example.com"))),
            emptyRegistry());
    ProfileContext.set(
        profileWith(
            List.of(
                new Profile.NotifyChannel(
                    "webhook", Map.of("url", "https://hooks.example.com/a")))));
    var input = MAPPER.createObjectNode();
    input.put("content", "hello");

    ToolResult result = guarded.execute(input);

    assertTrue(result.success());
    verify(adapter).send(any(), eq("hello"));
  }
}
