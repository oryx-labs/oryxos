package io.oryxos.tool.notify;

import java.util.Map;

/**
 * 通知目标：渠道类型 + 一份配置。具体是 webhook 地址还是别的认证信息，由实现类自己解释—— 对上层是黑盒。来源是 Profile 的 notify_channels 条目（type +
 * config），字段一一对应。
 */
public record NotifyTarget(String channelType, Map<String, String> config) {

  public NotifyTarget {
    config = config == null ? Map.of() : Map.copyOf(config);
  }
}
