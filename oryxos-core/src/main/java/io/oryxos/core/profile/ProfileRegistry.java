package io.oryxos.core.profile;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;

/**
 * Profile 内存索引，按 name 查找。
 *
 * <p>第 16 节只有"启动扫描"一条注册路径（由 {@link ProfileLoader} 构造）；29 节将补运行时 register() 方法。
 */
public class ProfileRegistry {

  private final Map<String, Profile> profiles;

  public ProfileRegistry(Map<String, Profile> profiles) {
    this.profiles = Map.copyOf(profiles);
  }

  public Optional<Profile> get(String name) {
    return Optional.ofNullable(profiles.get(name));
  }

  public Collection<Profile> all() {
    return profiles.values();
  }
}
