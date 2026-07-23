package io.oryxos.core.notify;

import java.util.List;
import java.util.Optional;

/**
 * 通知渠道注册表（跨模块契约，31 节）：全局通知出口的唯一真相源。上层只认这几个方法，不感知底层是 SQLite 还是别的。
 *
 * <p>与 tools 对齐——tools 全局注册、Agent 按名字引用；通知出口也全局注册（本表），Agent 的 notify 工具按 {@code name} 引用。实现在
 * oryxos-storage（JPA），web 层做 CRUD、oryxos-tool 的 notify 工具按名解析成 {@link NotifyChannelDef} 再发送。
 */
public interface NotifyChannelRegistry {

  /** 全部渠道（管理台列表 / notify 工具列可用渠道）。 */
  List<NotifyChannelDef> list();

  /** 按名字取一个渠道；不存在返回 empty（404 由 web 层决定）。 */
  Optional<NotifyChannelDef> find(String name);

  /** 名字是否已存在（create 冲突判定用）。 */
  boolean exists(String name);

  /** 新建或覆写（按 name 主键 upsert），返回落库后的定义。 */
  NotifyChannelDef save(NotifyChannelDef channel);

  /** 按名字删除（幂等）。 */
  void delete(String name);
}
