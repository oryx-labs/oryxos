package io.oryxos.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import io.oryxos.core.OryxTool;
import io.oryxos.core.ToolResult;
import io.oryxos.core.agent.ProfileContext;
import io.oryxos.core.profile.Profile;
import io.oryxos.tool.notify.NotifyChannelAdapter;
import io.oryxos.tool.notify.NotifyTarget;
import java.util.List;
import java.util.Map;

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

  public NotifyTools(Map<String, NotifyChannelAdapter> adapters) {
    this.adapters = Map.copyOf(adapters);
  }

  @Override
  public String getName() {
    return "notify";
  }

  @Override
  public String getDescription() {
    return "把一条消息推送到当前 Agent 配置好的通知渠道";
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
    // 沙箱检查位：24 节 Sandbox.enforce(HTTP_REQUEST, target.config().get("url")) 在此接线，
    // 与 http_post 共享同一份 http.allowed_domains 白名单——校验必须先于发送（宪法 VI）
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
