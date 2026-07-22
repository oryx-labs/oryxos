package io.oryxos.core.profile;

import io.oryxos.core.agent.AgentName;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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

  private final Map<String, Profile> profiles;

  public ProfileRegistry() {
    this.profiles = new ConcurrentHashMap<>();
  }

  public ProfileRegistry(Map<String, Profile> initial) {
    this.profiles = copyInitial(initial);
  }

  public Optional<Profile> get(String name) {
    String key = safeLockKey(name);
    if (key == null) {
      return Optional.empty();
    }
    Profile profile = profiles.get(key);
    return profile != null && profile.name().equals(name) ? Optional.of(profile) : Optional.empty();
  }

  /** 快照拷贝，不暴露内部可变集合。 */
  public Collection<Profile> all() {
    return List.copyOf(profiles.values());
  }

  /** 运行时登记（30 节 API 与启动扫描共用）；同名覆盖。 */
  public void register(Profile profile) {
    putProfile(profiles, profile);
  }

  private static Map<String, Profile> copyInitial(Map<String, Profile> initial) {
    int initialCapacity = initial == null ? 0 : initial.size();
    Map<String, Profile> copied = new ConcurrentHashMap<>(initialCapacity);
    if (initial != null) {
      initial.values().forEach(profile -> putProfile(copied, profile));
    }
    return copied;
  }

  private static void putProfile(Map<String, Profile> target, Profile profile) {
    Objects.requireNonNull(profile, "profile");
    String key = AgentName.parse(profile.name()).lockKey();
    target.compute(
        key,
        (ignored, existing) -> {
          if (existing != null && !existing.name().equals(profile.name())) {
            throw new IllegalArgumentException("Agent 名与已注册 Agent 存在大小写冲突: " + profile.name());
          }
          return profile;
        });
  }

  /** 运行时注销；存在并移除返回 true。 */
  public boolean remove(String name) {
    String key = safeLockKey(name);
    if (key == null) {
      return false;
    }
    Profile existing = profiles.get(key);
    return existing != null && existing.name().equals(name) && profiles.remove(key, existing);
  }

  public boolean exists(String name) {
    return get(name).isPresent();
  }

  /** True when the same filesystem/lock identity is registered, even with different casing. */
  public boolean existsIdentity(String name) {
    String key = safeLockKey(name);
    return key != null && profiles.containsKey(key);
  }

  private static String safeLockKey(String name) {
    try {
      return AgentName.parse(name).lockKey();
    } catch (IllegalArgumentException error) {
      return null;
    }
  }
}
