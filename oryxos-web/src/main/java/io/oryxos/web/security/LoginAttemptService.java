package io.oryxos.web.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 登录暴力破解防护（纯内存，无后台线程——宪法七同步模型）：同一「用户名|来源 IP」连续失败 {@value #MAX_FAILURES} 次后锁 {@link
 * #LOCK_DURATION}，窗口内成功登录清零。
 *
 * <p>键取用户名+IP 组合而非单用户名：攻击者猜不中密码也能靠失败次数把合法账号锁死（拒绝服务）；组合键下锁的只是 该来源，合法用户从自己的 IP 登录不受影响。反代未传
 * X-Forwarded-For 时所有请求同 IP，退化为按用户名锁——可接受的兜底。
 *
 * <p>惰性过期：查询时发现锁已到期或失败窗口已过即删条目，无定时清理线程；条目超过 {@link #SWEEP_THRESHOLD} 时顺手全表清一次过期项，防恶意大量用户名撑内存。
 */
public class LoginAttemptService {

  /** 连续失败次数上限，达到即锁定。 */
  static final int MAX_FAILURES = 5;

  /** 锁定时长；也是失败计数窗口——超窗后旧失败不再累计。 */
  static final Duration LOCK_DURATION = Duration.ofMinutes(15);

  /** 条目数超过此值时，写路径顺手清理全部过期条目（防恶意撑内存）。 */
  private static final int SWEEP_THRESHOLD = 10_000;

  /** 失败记录：窗口内累计次数 + 最近一次失败时间。 */
  private record FailureWindow(int failures, Instant lastFailure) {}

  private final Map<String, FailureWindow> attempts = new ConcurrentHashMap<>();
  private final Clock clock;

  public LoginAttemptService() {
    this(Clock.systemUTC());
  }

  /** 测试注入可控时钟。 */
  LoginAttemptService(Clock clock) {
    this.clock = clock;
  }

  /** 该键当前是否处于锁定期。到期条目顺手删（惰性过期）。 */
  public boolean isBlocked(String key) {
    FailureWindow window = attempts.get(key);
    if (window == null) {
      return false;
    }
    if (expired(window)) {
      attempts.remove(key, window);
      return false;
    }
    return window.failures() >= MAX_FAILURES;
  }

  /** 记一次失败：窗口内累计，超窗重新从 1 计。 */
  public void onFailure(String key) {
    Instant now = clock.instant();
    attempts.compute(
        key,
        (k, window) ->
            window == null || expired(window)
                ? new FailureWindow(1, now)
                : new FailureWindow(window.failures() + 1, now));
    if (attempts.size() > SWEEP_THRESHOLD) {
      attempts.entrySet().removeIf(entry -> expired(entry.getValue()));
    }
  }

  /** 登录成功清零该键。 */
  public void onSuccess(String key) {
    attempts.remove(key);
  }

  private boolean expired(FailureWindow window) {
    return window.lastFailure().plus(LOCK_DURATION).isBefore(clock.instant());
  }
}
