package io.oryxos.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.oryxos.core.OryxTool;
import io.oryxos.core.ToolResult;
import io.oryxos.core.agent.ProfileContext;
import io.oryxos.core.notify.NotifyChannelDef;
import io.oryxos.core.notify.NotifyChannelRegistry;
import io.oryxos.core.profile.Profile;
import io.oryxos.tool.notify.NotifyChannelAdapter;
import io.oryxos.tool.notify.NotifyTarget;
import io.oryxos.tool.sandbox.ActionType;
import io.oryxos.tool.sandbox.Sandbox;
import io.oryxos.tool.sandbox.SandboxAction;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 内置工具 notify：把一条消息推送到当前 Agent 配置好的通知渠道。
 *
 * <p>OryxTool 形态（research D3）：@Tool 注册机制归 20 节，本形态可直接被 17 节 ToolExecutor 执行与审计（tool_invocations
 * 走既有路径，不新增审计逻辑）。渠道来源是当前 Profile 的 notify_channels——webhook 地址是运行时配置，不是模型需要知道的信息（FR-005）。
 */
public class NotifyTools implements OryxTool {

  /** channel 参数的"用默认渠道"字面量（课件示例用词）。 */
  private static final String DEFAULT_CHANNEL = "default";

  /** channelType → 实现（webhook/wecom/feishu/dingtalk…）；多档并存按 type 路由（课件 6.4 路一）。 */
  private final Map<String, NotifyChannelAdapter> adapters;

  /** 推送前过 HTTP 域名白名单（宪法 VI）——与 http_post 共享同一份 http.allowed_domains（24 节接线）。 */
  private final Sandbox sandbox;

  /** 全局通知渠道注册表（31 节）：channel 传渠道名时按它解析成 {type,url}，不再依赖 AGENT.md 内联。 */
  private final NotifyChannelRegistry channelRegistry;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "channelRegistry 是 Spring 注入的共享单例，构造注入共享同一引用正是意图（与 adapters/sandbox 同）")
  public NotifyTools(
      Map<String, NotifyChannelAdapter> adapters,
      Sandbox sandbox,
      NotifyChannelRegistry channelRegistry) {
    this.adapters = Map.copyOf(adapters);
    this.sandbox = sandbox;
    this.channelRegistry = channelRegistry;
  }

  @Override
  public String getName() {
    return "notify";
  }

  @Override
  public String getDescription() {
    // 动态列出当前已注册的渠道名，模型据此选 channel（31 节：出口全局管理、按名引用）
    List<NotifyChannelDef> registered = channelRegistry.list();
    if (registered.isEmpty()) {
      return "把一条消息推送到指定通知渠道。channel 传渠道名；当前无已注册渠道——去管理台「Notify 渠道」里新建。";
    }
    String names =
        registered.stream().map(NotifyChannelDef::name).collect(Collectors.joining(", "));
    return "把一条消息推送到指定通知渠道。channel 传渠道名，当前可用：" + names;
  }

  @Override
  public String getInputSchema() {
    return """
        {
          "type": "object",
          "properties": {
            "content": {"type": "string", "description": "要推送的内容"},
            "channel": {"type": "string", "description": "渠道类型；缺省用第一个配置的渠道"}
          },
          "required": ["content"]
        }""";
  }

  @Override
  public ToolResult execute(JsonNode input) {
    JsonNode contentNode = input.get("content");
    if (contentNode == null || contentNode.asText().isEmpty()) {
      return ToolResult.error("notify 缺少必填参数 content", false);
    }
    String channel = input.hasNonNull("channel") ? input.get("channel").asText() : null;

    // 新模型（31 节）：channel 是全局注册表里的渠道名 → 按名解析成 {type,url}，与 Agent 内联配置无关。
    // Agent 正文用自然语言指定"发到 <渠道名>"，模型把名字传进来即可。
    if (channel != null && !channel.isBlank()) {
      Optional<NotifyChannelDef> registered = channelRegistry.find(channel);
      if (registered.isPresent()) {
        NotifyChannelDef def = registered.get();
        NotifyChannelAdapter adapter = adapters.get(def.type());
        if (adapter == null) {
          return ToolResult.error(
              "渠道 " + def.name() + " 的类型 " + def.type() + " 没有对应实现（已装配: " + adapters.keySet() + "）",
              false);
        }
        // 校验先于发送（宪法 VI）：推送地址过 HTTP 域名白名单，与 http_post 共享 http.allowed_domains。
        sandbox.enforce(new SandboxAction(ActionType.HTTP_REQUEST, def.url()));
        adapter.send(new NotifyTarget(def.type(), Map.of("url", def.url())), contentNode.asText());
        return ToolResult.ok("已推送");
      }
      // channel 给了名字但注册表没有 → 落到下面的兼容路径（按 type 匹配 Agent 内联渠道）
    }

    // 兼容老模型：从当前 Profile 的内联 notify_channels 解析（channel 为空或按 type 匹配）
    Profile profile = ProfileContext.current();
    if (profile == null) {
      return ToolResult.error("当前无 Agent 上下文，无法解析通知渠道", false);
    }
    List<Profile.NotifyChannel> channels = profile.notifyChannels();
    if (channels.isEmpty()) {
      // 明确报错而非静默失败——Agent 不能以为发出去了（课件守点）
      return ToolResult.error("Profile " + profile.name() + " 未配置 notify_channels，无处可推", false);
    }
    Profile.NotifyChannel resolved = resolveChannel(channels, channel);
    if (resolved == null) {
      return ToolResult.error("notify_channels 中不存在类型为 " + channel + " 的渠道（不回退默认，避免消息发错地方）", false);
    }
    NotifyChannelAdapter adapter = adapters.get(resolved.type());
    if (adapter == null) {
      return ToolResult.error(
          "渠道类型 " + resolved.type() + " 没有对应的通知实现（已装配: " + adapters.keySet() + "）", false);
    }
    NotifyTarget target = new NotifyTarget(resolved.type(), resolved.config());
    // 校验必须先于发送（宪法 VI）：推送地址过 HTTP 域名白名单，与 http_post 共享同一份 http.allowed_domains。
    // enforce 不过抛 SandboxViolationException 不 catch——上抛至 ToolExecutor 走既有失败审计（success=false），
    // 与 FileTools/HttpTools 同一路径，不为校验单独新增审计逻辑。
    sandbox.enforce(new SandboxAction(ActionType.HTTP_REQUEST, resolved.config().get("url")));
    adapter.send(target, contentNode.asText());
    return ToolResult.ok("已推送");
  }

  /** channel 空白或 "default" → 第一个渠道；否则按 NotifyChannel.type 匹配（clarify 1）。 */
  private static Profile.NotifyChannel resolveChannel(
      List<Profile.NotifyChannel> channels, String channel) {
    if (channel == null || channel.isBlank() || DEFAULT_CHANNEL.equals(channel)) {
      return channels.get(0);
    }
    for (Profile.NotifyChannel candidate : channels) {
      if (channel.equals(candidate.type())) {
        return candidate;
      }
    }
    return null;
  }
}
