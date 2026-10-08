package io.oryxos.storage;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

/**
 * REST API Key 管理（018-rest-api-key）：生成/校验/吊销/盘点。
 *
 * <p>明文 Key = {@code oryx_} + 42 位 base62 随机串（SecureRandom，约 250 bit 熵）。持久化只存 SHA-256 hex 哈希
 * 与可辨识前缀（{@code oryx_}+随机部分前 8 位）；明文仅 {@link #create} 返回值出现一次，NEVER 落库/日志（宪法 VI）。
 *
 * <p>哈希选 SHA-256 而非 BCrypt：高熵随机 Key 无需慢哈希，BCrypt 每请求 ~100ms 会击穿「认证开销无感」（research R1， 与 {@code
 * WebUserService} 的 BCrypt 口径不同——那是给低熵人类密码的，各自正确）。
 *
 * <p>校验先对明文算 SHA-256 再按哈希查库（R2）：输入经单向变换，索引比较的计时信息不构成逐位 oracle；命中后 {@link MessageDigest#isEqual}
 * 恒定时间复核兜底（FR-010）。不存在与已吊销走同一失败路径（防探测，FR-004）。
 *
 * <p>{@code last_used_at} 在请求线程内同步更新（宪法 VII 不引入异步），经 60s 内存节流防 SQLite 写放大；更新失败
 * 仅记日志不阻断请求（FR-009/R6）。节流表是进程内缓存非状态，重启丢失无害。
 *
 * <p>plain class（非 @Service），构造注入 repository，由 {@code OryxOsRuntime} @Bean 装配。镜像 {@code
 * WebUserService} 风格，不引 oryxos-web 类（避免 storage→web 反向依赖）。
 */
public class ApiKeyService {

  private static final Logger LOG = LoggerFactory.getLogger(ApiKeyService.class);

  /** 明文 Key 固定前缀——密钥扫描工具可辨识（业界 ghp_/sk_ 同型，R1）。 */
  public static final String PLAINTEXT_PREFIX = "oryx_";

  /** 随机部分长度（base62，42 位约 250 bit 熵）。 */
  private static final int RANDOM_LENGTH = 42;

  /** key_prefix 列存的随机部分头部位数（供 list/日志对账，不足以还原 Key）。 */
  private static final int DISPLAY_RANDOM_CHARS = 8;

  private static final String BASE62 =
      "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

  private static final int MAX_NAME_LENGTH = 64;

  /** last_used_at 落库节流窗口（秒）——分钟级治理信号，逐请求写库无必要（R6）。 */
  private static final long LAST_USED_THROTTLE_SECONDS = 60;

  private final ApiKeyRepository repository;
  private final SecureRandom secureRandom = new SecureRandom();

  /** 节流缓存：key id → 上次落库时间。进程内缓存非状态（宪法 VIII 不冲突）。 */
  private final Map<Long, Instant> lastPersistedUse = new ConcurrentHashMap<>();

  /** 创建结果：实体 + 明文（明文仅此一次，调用方负责只输出到 stdout）。 */
  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = {"EI_EXPOSE_REP", "EI_EXPOSE_REP2"},
      justification =
          "CreatedKey 是 create() 的一次性返回值载体，携带刚落库的同一 ApiKey 实体引用正是意图"
              + "（调用方需读 name/prefix 展示）；不复制以免第二份实体状态漂移（镜像既有 SuppressFBWarnings 模式）。")
  public record CreatedKey(ApiKey key, String plaintext) {}

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "repository 为 Spring 注入的共享单例，构造注入存同一引用正是意图（镜像既有 WebUserService 模式）。")
  public ApiKeyService(ApiKeyRepository repository) {
    this.repository = repository;
  }

  /** 生成新 Key；重名/名称非法抛 IllegalArgumentException。返回值含明文，仅此一次。 */
  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "CRLF_INJECTION_LOGS",
      justification =
          "日志变量仅 name 与 prefix：name 经 validateName 拒绝一切空白字符（含 CR/LF），prefix 为内部生成的"
              + " oryx_+base62，均不可能携带 CRLF；误报。")
  @Transactional(rollbackFor = Exception.class)
  public CreatedKey create(String name) {
    validateName(name);
    String trimmed = name.strip();
    if (repository.existsByName(trimmed)) {
      throw new IllegalArgumentException("api key '" + trimmed + "' already exists");
    }
    String random = randomBase62();
    String plaintext = PLAINTEXT_PREFIX + random;
    ApiKey key = new ApiKey();
    key.setName(trimmed);
    key.setKeyPrefix(PLAINTEXT_PREFIX + random.substring(0, DISPLAY_RANDOM_CHARS));
    key.setKeyHash(sha256Hex(plaintext));
    ApiKey saved = repository.save(key);
    LOG.info("api key created: name={}, prefix={}", saved.getName(), saved.getKeyPrefix());
    return new CreatedKey(saved, plaintext);
  }

  /**
   * 校验明文 Key：格式错/不存在/已吊销均返 false（同一路径同一结果，防探测）。通过后同步节流更新 last_used_at， 更新失败仅日志不影响返回值。
   *
   * <p>**刻意不加 {@code @Transactional}。** {@code last_used_at} 是一次可失败的治理写入，FR-009 要求它失败
   * 不得阻断请求；而方法级事务会把这个要求变成不可能：{@code repository.save(key)} 在事务内退化成一次 merge，真正的 UPDATE
   * 要等事务提交时才发出——那已经在 {@code touchLastUsed} 的 try/catch 之外。写竞争下 异常从提交点抛出（或把事务标成 rollback-only
   * 后在提交点变成 {@code UnexpectedRollbackException}）， 两种走法都把一把有效 Key 的认证打挂。不开事务时 {@code save}
   * 自带的事务在调用点提交，失败正好落在 try/catch 里。读侧不需要事务：{@code findByKeyHash} 单行读取，Spring Data 自带只读事务。
   *
   * <p>调用方（{@code ApiKeyAuthFilter}）也没有外层事务，与 {@code AuthEventRecorder.recordBestEffort} 等同类
   * best-effort 写入的调用形态一致。
   */
  public boolean verify(String plaintext) {
    if (plaintext == null || plaintext.isBlank() || !plaintext.startsWith(PLAINTEXT_PREFIX)) {
      return false;
    }
    String presentedHash = sha256Hex(plaintext);
    ApiKey key = repository.findByKeyHash(presentedHash).orElse(null);
    if (key == null || !key.isActive()) {
      return false;
    }
    // 恒定时间复核兜底（FR-010）；hex 均为本地计算的规范形式
    if (!MessageDigest.isEqual(
        presentedHash.getBytes(StandardCharsets.US_ASCII),
        key.getKeyHash().getBytes(StandardCharsets.US_ASCII))) {
      return false;
    }
    touchLastUsed(key);
    return true;
  }

  /**
   * 解析明文 Key 对应的 Key 名称（039-identity-authorization）：用于把请求主体标识成「哪把 Key」。
   *
   * <p>只返回名称，绝不返回明文或哈希——审计里允许出现名称，不允许出现凭证。校验口径与 {@link #verify(String)} 完全一致（前缀 → SHA-256 → 命中 →
   * 未吊销 → 恒定时间复核），保证「verify 通过」与「能解析出名称」不会出现 两种判定，从而避免主体标识与认证结论相矛盾。
   *
   * <p>与 verify 的刻意差异：不更新 {@code last_used_at}。本方法在同一请求里通常紧跟 verify 调用，重复触碰只会 多一次无意义写入（verify 已有
   * 60s 节流）；且解析动作本身不应被记作「使用」。
   *
   * @return Key 名称；格式错 / 不存在 / 已吊销均返 {@code null}
   */
  @Transactional(readOnly = true)
  public String findNameByPlaintext(String plaintext) {
    if (plaintext == null || plaintext.isBlank() || !plaintext.startsWith(PLAINTEXT_PREFIX)) {
      return null;
    }
    String presentedHash = sha256Hex(plaintext);
    ApiKey key = repository.findByKeyHash(presentedHash).orElse(null);
    if (key == null || !key.isActive()) {
      return null;
    }
    if (!MessageDigest.isEqual(
        presentedHash.getBytes(StandardCharsets.US_ASCII),
        key.getKeyHash().getBytes(StandardCharsets.US_ASCII))) {
      return null;
    }
    return key.getName();
  }

  /** 吊销；不存在抛 IllegalArgumentException。已吊销幂等返 false，新吊销返 true。 */
  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "CRLF_INJECTION_LOGS",
      justification = "日志变量为库中 name（建 Key 时已经 validateName 拒绝空白字符）与内部生成的 prefix，均不可能携带 CRLF；误报。")
  @Transactional(rollbackFor = Exception.class)
  public boolean revoke(String name) {
    ApiKey key =
        repository
            .findByName(normalizeName(name))
            .orElseThrow(() -> new IllegalArgumentException("api key '" + name + "' not found"));
    if (!key.isActive()) {
      return false;
    }
    key.setRevokedAt(Instant.now());
    repository.save(key);
    LOG.info("api key revoked: name={}, prefix={}", key.getName(), key.getKeyPrefix());
    return true;
  }

  /** 列全部 Key（按 name 排序，含已吊销）；仅返回实体（无明文可泄）。 */
  public List<ApiKey> list() {
    return repository.findAll().stream().sorted(Comparator.comparing(ApiKey::getName)).toList();
  }

  /** 启动校验用：是否存在有效（未吊销）Key（FR-012）。 */
  public boolean hasActiveKey() {
    return repository.findAll().stream().anyMatch(ApiKey::isActive);
  }

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "CRLF_INJECTION_LOGS",
      justification = "日志变量为内部生成的 oryx_+base62 前缀，不可能携带 CRLF；误报。")
  private void touchLastUsed(ApiKey key) {
    Instant now = Instant.now();
    Long id = key.getId();
    Instant last = id == null ? null : lastPersistedUse.get(id);
    if (last != null && last.plusSeconds(LAST_USED_THROTTLE_SECONDS).isAfter(now)) {
      return;
    }
    try {
      key.setLastUsedAt(now);
      repository.save(key);
      if (id != null) {
        lastPersistedUse.put(id, now);
      }
    } catch (RuntimeException e) {
      // FR-009：治理信号更新失败不阻断业务请求；只记前缀不记明文
      LOG.warn("failed to update last_used_at for api key prefix={}", key.getKeyPrefix(), e);
    }
  }

  private String randomBase62() {
    StringBuilder sb = new StringBuilder(RANDOM_LENGTH);
    for (int i = 0; i < RANDOM_LENGTH; i++) {
      sb.append(BASE62.charAt(secureRandom.nextInt(BASE62.length())));
    }
    return sb.toString();
  }

  private static String sha256Hex(String plaintext) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(plaintext.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }

  /**
   * 名字归一：{@code create} 用 strip 后的值落库与判重，查找（{@code revoke}）必须用同一形式， 否则「同一个字符串建得出、却吊销不掉」。null
   * 交由查找返回空，与既有 not found 文案一致。
   */
  private static String normalizeName(String name) {
    return name == null ? null : name.strip();
  }

  private static void validateName(String name) {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("api key name must not be empty");
    }
    String trimmed = name.strip();
    if (trimmed.length() > MAX_NAME_LENGTH) {
      throw new IllegalArgumentException(
          "api key name must be at most " + MAX_NAME_LENGTH + " characters");
    }
    if (trimmed.chars().anyMatch(Character::isWhitespace)) {
      throw new IllegalArgumentException("api key name must not contain whitespace");
    }
  }
}
