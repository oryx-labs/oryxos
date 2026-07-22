package io.oryxos.core.provider;

import java.util.List;
import java.util.Optional;

/**
 * Provider 注册表（跨模块契约，31 节动态 provider）：LLM 接入点的唯一真相源，可运行时 CRUD。
 *
 * <p>与宪法 III「显式 name→ChatModel 映射」一致——仍是按 name 的显式查找，只是从"启动静态建好"变成"运行时可变、按名动态建"。 实现在
 * oryxos-storage（SQLite），web 层做 CRUD，oryxos-provider 的 {@code SpringAiProviderServiceImpl} 按名从这里取
 * {@link ProviderDef} 再构建/缓存 ChatModel。
 */
public interface ProviderRegistry {

  List<ProviderDef> list();

  Optional<ProviderDef> find(String name);

  boolean exists(String name);

  /** 新建或覆写（按 name 主键 upsert），返回落库后的定义。 */
  ProviderDef save(ProviderDef provider);

  void delete(String name);
}
