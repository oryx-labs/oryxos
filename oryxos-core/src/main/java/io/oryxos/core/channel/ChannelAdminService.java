package io.oryxos.core.channel;

import io.oryxos.core.profile.ProfileRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 入站渠道管理（017 T019）：增/改/删 + 启动恢复，落盘即生效、无需重启 OryxOS（FR-013）。
 *
 * <p>骨架复刻 {@code McpServerAdminService}：{@code synchronized} 串行化管理操作；变更顺序固定为 校验 → 落盘 →
 * <b>先断开旧连接</b> → 建立新连接（避免新旧连接并存打架）。校验失败的操作不落盘、不上线，点名报错（SC-008）。
 *
 * <p>适配器创建经 type → 工厂映射（飞书工厂由 Runtime 装配注入）——core 不依赖具体渠道模块（依赖倒置）， 新增渠道类型 = 注册一个新工厂，core 零修改。
 */
public class ChannelAdminService {

  private static final Logger LOG = LoggerFactory.getLogger(ChannelAdminService.class);

  private final ChannelConfigLoader loader;
  private final InboundChannelRegistry registry;
  private final ProfileRegistry profileRegistry;
  private final Map<String, Function<ChannelConfig, InboundChannelAdapter>> adapterFactories;

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "协作者均为 Runtime 装配的单例；工厂映射由装配层构造后只读使用")
  public ChannelAdminService(
      ChannelConfigLoader loader,
      InboundChannelRegistry registry,
      ProfileRegistry profileRegistry,
      Map<String, Function<ChannelConfig, InboundChannelAdapter>> adapterFactories) {
    this.loader = loader;
    this.registry = registry;
    this.profileRegistry = profileRegistry;
    this.adapterFactories = adapterFactories;
  }

  /** 启动恢复：加载全部配置逐条上线；单条失败登记 ERROR（点名原因）不阻断其余（FR-013 不带病上线、不影响其余功能）。 */
  public synchronized void startAll() {
    List<ChannelConfig> configs;
    try {
      configs = loader.load();
    } catch (RuntimeException e) {
      LOG.error("channels.yaml 加载失败，入站渠道全部停摆: {}", sanitize(e.getMessage()));
      return;
    }
    for (ChannelConfig config : configs) {
      startOne(config);
    }
  }

  /**
   * 停止全部运行中渠道（进程关闭钩子）。039 US2：与 {@link #stopOne} 对齐——先释放独连型渠道的属主租约 （cancel 续租 +
   * releaseChannel），本副本优雅退出后新属主立刻可接管，不再干等 TTL 过期（此前 stopAll 漏调 unmanage，滚动升级窗口内企微类渠道最长断联一个租约周期）。
   */
  public synchronized void stopAll() {
    ChannelLeaseCoordinator coordinator = channelLeaseCoordinator;
    for (ChannelStatus status : registry.statusAll()) {
      if (coordinator != null) {
        coordinator.unmanage(status.name());
      }
      registry.get(status.name()).ifPresent(InboundChannelAdapter::stop);
    }
  }

  /** 新增渠道并立即上线；name 冲突 / 校验失败不落盘。传入必须是 raw 口径（凭证保留 ${} 字面量）。 */
  public synchronized ChannelConfig add(ChannelConfig raw) {
    raw.validateShape();
    List<ChannelConfig> existing = new ArrayList<>(loader.loadRaw());
    if (existing.stream().anyMatch(c -> c.name().equals(raw.name()))) {
      throw new IllegalArgumentException("渠道已存在: " + raw.name());
    }
    ChannelConfig resolved = loader.resolve(raw);
    if (raw.enabled()) {
      validateForLaunch(resolved); // 停用条目允许凭证暂缺（不上线即无风险），启用时再校验
    }
    existing.add(raw);
    loader.save(existing);
    startOne(resolved);
    return raw;
  }

  /** 更新渠道：先断开旧连接再按新配置上线（禁止新旧并存）。 */
  public synchronized ChannelConfig update(String name, ChannelConfig raw) {
    raw.validateShape();
    if (!raw.name().equals(name)) {
      throw new IllegalArgumentException("渠道名不可变更: " + name);
    }
    List<ChannelConfig> existing = new ArrayList<>(loader.loadRaw());
    int idx = indexOf(existing, name);
    if (idx < 0) {
      throw new IllegalArgumentException("渠道不存在: " + name);
    }
    ChannelConfig merged = preserveGovernance(raw, existing.get(idx));
    ChannelConfig resolved = loader.resolve(merged);
    if (merged.enabled()) {
      validateForLaunch(resolved);
    }
    stopOne(name);
    existing.set(idx, merged);
    loader.save(existing);
    startOne(resolved);
    return merged;
  }

  /** 更新未携带治理块时保留既有块，避免 CRUD 把 channels.yaml 里的 governance 抹掉。显式空块表示清除。 */
  private static ChannelConfig preserveGovernance(ChannelConfig incoming, ChannelConfig previous) {
    if (incoming.governance() != null) {
      return incoming;
    }
    return incoming.withGovernance(previous.governance());
  }

  /** 删除渠道：断开连接并从配置移除。 */
  public synchronized void remove(String name) {
    List<ChannelConfig> existing = new ArrayList<>(loader.loadRaw());
    int idx = indexOf(existing, name);
    if (idx < 0) {
      throw new IllegalArgumentException("渠道不存在: " + name);
    }
    stopOne(name);
    existing.remove(idx);
    loader.save(existing);
    registry.unregister(name);
  }

  /** 全部渠道实时状态（REST /api/v1/channels/status 数据源）。 */
  public List<ChannelStatus> status() {
    return registry.statusAll();
  }

  /** raw 配置列表（REST 列表数据源；凭证保持 ${} 字面量，控制器负责掩码）。 */
  public List<ChannelConfig> listRaw() {
    return loader.loadRaw();
  }

  /** 上线前校验（点名报错）：type 已注册 / 凭证已解析 / 绑定 Agent 存在。 */
  private void validateForLaunch(ChannelConfig resolved) {
    if (!adapterFactories.containsKey(resolved.type())) {
      throw new IllegalArgumentException(
          "渠道 "
              + resolved.name()
              + " 的 type 不支持: "
              + resolved.type()
              + "（可用: "
              + adapterFactories.keySet()
              + "）");
    }
    resolved.validateCredentialsResolved();
    if (profileRegistry.get(resolved.agent()).isEmpty()) {
      throw new IllegalArgumentException(
          "渠道 " + resolved.name() + " 绑定的 Agent " + resolved.agent() + " 不存在");
    }
  }

  /** 026：独连型渠道类型（单连接互踢语义，需属主协调）；集群档由 setChannelLeaseCoordinator 装配。 */
  private static final java.util.Set<String> EXCLUSIVE_CONNECTION_TYPES = java.util.Set.of("wecom");

  private volatile ChannelLeaseCoordinator channelLeaseCoordinator;

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "注入的协调器是装配层构造的共享组件（Spring Bean 语义），本就不应防御性拷贝。")
  public void setChannelLeaseCoordinator(ChannelLeaseCoordinator coordinator) {
    this.channelLeaseCoordinator = coordinator;
  }

  /** 单渠道上线：enabled=false 登记 DISABLED；启动失败登记 ERROR 点名原因，不上抛（不阻断其余渠道）。 */
  private void startOne(ChannelConfig resolved) {
    if (!resolved.enabled()) {
      registry.registerOffline(
          new ChannelStatus(
              resolved.name(),
              resolved.type(),
              resolved.agent(),
              ChannelStatus.State.DISABLED,
              null));
      return;
    }
    // 026：集群档下独连型渠道（企微）交给属主协调——持租约副本建连，其余 STANDBY 待接管
    ChannelLeaseCoordinator coordinator = channelLeaseCoordinator;
    if (coordinator != null && EXCLUSIVE_CONNECTION_TYPES.contains(resolved.type())) {
      startExclusive(resolved, coordinator);
      return;
    }
    try {
      validateForLaunch(resolved);
      InboundChannelAdapter adapter = adapterFactories.get(resolved.type()).apply(resolved);
      adapter.start();
      registry.register(adapter);
      LOG.info(
          "入站渠道 {} 已上线（type={} agent={}）",
          sanitize(resolved.name()),
          sanitize(resolved.type()),
          sanitize(resolved.agent()));
    } catch (RuntimeException e) {
      LOG.error("入站渠道 {} 上线失败: {}", sanitize(resolved.name()), sanitize(e.getMessage()));
      registry.registerOffline(
          ChannelStatus.error(
              resolved.name(), resolved.type(), resolved.agent(), sanitize(e.getMessage())));
    }
  }

  /** 独连型渠道的属主化上线（026）：先登记 STANDBY，属主循环拿到租约才真正建连。 */
  private void startExclusive(ChannelConfig resolved, ChannelLeaseCoordinator coordinator) {
    try {
      validateForLaunch(resolved);
      InboundChannelAdapter adapter = adapterFactories.get(resolved.type()).apply(resolved);
      java.util.concurrent.atomic.AtomicBoolean connected =
          new java.util.concurrent.atomic.AtomicBoolean(false);
      registry.registerOffline(
          new ChannelStatus(
              resolved.name(),
              resolved.type(),
              resolved.agent(),
              ChannelStatus.State.STANDBY,
              null));
      coordinator.manage(
          resolved.name(),
          () -> {
            adapter.start();
            connected.set(true);
            registry.register(adapter);
            LOG.info("独连型渠道 {} 已由本副本接管上线", sanitize(resolved.name()));
          },
          () -> {
            adapter.stop();
            connected.set(false);
            registry.registerOffline(
                new ChannelStatus(
                    resolved.name(),
                    resolved.type(),
                    resolved.agent(),
                    ChannelStatus.State.STANDBY,
                    null));
          },
          connected::get);
    } catch (RuntimeException e) {
      LOG.error("独连型渠道 {} 属主化上线失败: {}", sanitize(resolved.name()), sanitize(e.getMessage()));
      registry.registerOffline(
          ChannelStatus.error(
              resolved.name(), resolved.type(), resolved.agent(), sanitize(e.getMessage())));
    }
  }

  private void stopOne(String name) {
    ChannelLeaseCoordinator coordinator = channelLeaseCoordinator;
    if (coordinator != null) {
      coordinator.unmanage(name);
    }
    registry.get(name).ifPresent(InboundChannelAdapter::stop);
    registry.unregister(name);
  }

  private static int indexOf(List<ChannelConfig> configs, String name) {
    for (int i = 0; i < configs.size(); i++) {
      if (configs.get(i).name().equals(name)) {
        return i;
      }
    }
    return -1;
  }

  private static String sanitize(String value) {
    return value == null ? "" : value.replace('\r', '_').replace('\n', '_');
  }
}
