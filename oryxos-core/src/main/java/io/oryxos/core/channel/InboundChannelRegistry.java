package io.oryxos.core.channel;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 运行中入站渠道注册表：渠道名 → 适配器实例；另记录未能上线渠道的 ERROR 状态（点名原因可查，SC-008）。
 *
 * <p>{@link #statusAll()} 基于注册表实时计算，不 snapshot——管理台增删渠道必须立刻反映到状态端点 （#203 活视图教训，参照
 * ToolRegistry.asMap）。
 *
 * <p>一个渠道在任一时刻只处于一种状态，所以这里用<b>一张表</b>存「适配器或离线状态」，而不是并行两张表： 两张表意味着每次登记要动两处，读方可能落在两次写之间，看到一个渠道同时在两边
 * —— 状态端点于是 同一渠道返回两行、且两行互相矛盾。单表的每次更新都是一次 {@code put}，读者看到的必然是某个完整状态。
 */
public class InboundChannelRegistry {

  /** 一个渠道的当前状态：要么是一个活着的适配器，要么是一条离线状态（未上线：ERROR 点名原因 / DISABLED 停用）。 */
  private sealed interface Entry permits Online, Offline {}

  private record Online(InboundChannelAdapter adapter) implements Entry {}

  private record Offline(ChannelStatus status) implements Entry {}

  private final Map<String, Entry> entries = new ConcurrentHashMap<>();

  /** 登记一个已启动的适配器；同名离线记录被这一次 {@code put} 一并取代。 */
  public void register(InboundChannelAdapter adapter) {
    entries.put(adapter.name(), new Online(adapter));
  }

  /** 登记一个未上线渠道的状态（ERROR 带点名原因 / DISABLED 停用）；同名适配器记录被一并取代。 */
  public void registerOffline(ChannelStatus status) {
    entries.put(status.name(), new Offline(status));
  }

  /** 移除一个渠道的登记（运行中或离线态皆可）。 */
  public void unregister(String name) {
    entries.remove(name);
  }

  public Optional<InboundChannelAdapter> get(String name) {
    return entries.get(name) instanceof Online online
        ? Optional.of(online.adapter())
        : Optional.empty();
  }

  /** 全部渠道实时状态：运行中的问适配器，未上线的返回登记的离线状态。每个渠道名至多一行。 */
  public List<ChannelStatus> statusAll() {
    List<ChannelStatus> out = new ArrayList<>();
    for (Entry entry : entries.values()) {
      if (entry instanceof Online online) {
        out.add(online.adapter().status());
      } else if (entry instanceof Offline offline) {
        out.add(offline.status());
      }
    }
    out.sort(java.util.Comparator.comparing(ChannelStatus::name));
    return out;
  }
}
