package io.oryxos.core.profile;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Profile 内存索引，按 name 查找。
 *
 * <p>16 节只有"启动扫描"一条注册路径（不可变）；29 节改为可变并发 Map，补运行时 {@link #register}/{@link #remove}/{@link
 * #exists}——让"启动扫描"与"运行时新增 Agent"走同一份注册表。非法配置的校验在上游 {@code AgentLoader.deriveProfile}/{@code
 * ProfileLoader.fromMap} 完成（同一异常同一消息），本类只负责存取。
 */
public class ProfileRegistry {

  private final Map<String, Profile> profiles = new ConcurrentHashMap<>();

  public ProfileRegistry() {}

  public ProfileRegistry(Map<String, Profile> initial) {
    if (initial != null) {
      profiles.putAll(initial);
    }
  }

  public Optional<Profile> get(String name) {
    return Optional.ofNullable(profiles.get(name));
  }

  /** 快照拷贝，不暴露内部可变集合。 */
  public Collection<Profile> all() {
    return List.copyOf(profiles.values());
  }

  /** 运行时登记（30 节 API 与启动扫描共用）；同名覆盖。 */
  public void register(Profile profile) {
    profiles.put(profile.name(), profile);
  }

  /** 运行时注销；存在并移除返回 true。 */
  public boolean remove(String name) {
    return profiles.remove(name) != null;
  }

  public boolean exists(String name) {
    return profiles.containsKey(name);
  }
}
