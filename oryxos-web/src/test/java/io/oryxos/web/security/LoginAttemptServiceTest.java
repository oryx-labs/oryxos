package io.oryxos.web.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** LoginAttemptService 时间窗口行为钉死：可控时钟下验证锁定、到期解锁、超窗重计。 */
class LoginAttemptServiceTest {

  private static final String KEY = "admin|127.0.0.1";

  private AtomicReference<Instant> now;
  private LoginAttemptService service;

  @BeforeEach
  void setUp() {
    now = new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));
    Clock clock =
        new Clock() {
          @Override
          public ZoneOffset getZone() {
            return ZoneOffset.UTC;
          }

          @Override
          public Clock withZone(java.time.ZoneId zone) {
            return this;
          }

          @Override
          public Instant instant() {
            return now.get();
          }
        };
    service = new LoginAttemptService(clock);
  }

  @Test
  @DisplayName("未达上限不锁_达到MAX_FAILURES即锁")
  void blocksOnlyAfterMaxFailures() {
    for (int i = 0; i < LoginAttemptService.MAX_FAILURES - 1; i++) {
      service.onFailure(KEY);
      assertThat(service.isBlocked(KEY)).isFalse();
    }
    service.onFailure(KEY);
    assertThat(service.isBlocked(KEY)).isTrue();
  }

  @Test
  @DisplayName("锁定到期后自动解锁且条目清除")
  void unlocksAfterLockDurationElapses() {
    for (int i = 0; i < LoginAttemptService.MAX_FAILURES; i++) {
      service.onFailure(KEY);
    }
    assertThat(service.isBlocked(KEY)).isTrue();

    now.updateAndGet(t -> t.plus(LoginAttemptService.LOCK_DURATION).plusSeconds(1));
    assertThat(service.isBlocked(KEY)).isFalse();
  }

  @Test
  @DisplayName("超窗后旧失败不累计_重新从1计")
  void staleFailuresRestartCounting() {
    for (int i = 0; i < LoginAttemptService.MAX_FAILURES - 1; i++) {
      service.onFailure(KEY);
    }
    // 窗口过期后再失败：应重新从 1 计，而非叠加到第 5 次触发锁定。
    now.updateAndGet(t -> t.plus(LoginAttemptService.LOCK_DURATION).plusSeconds(1));
    service.onFailure(KEY);
    assertThat(service.isBlocked(KEY)).isFalse();
  }

  @Test
  @DisplayName("成功登录清零计数")
  void successClearsCounter() {
    for (int i = 0; i < LoginAttemptService.MAX_FAILURES - 1; i++) {
      service.onFailure(KEY);
    }
    service.onSuccess(KEY);
    service.onFailure(KEY);
    assertThat(service.isBlocked(KEY)).isFalse();
  }

  @Test
  @DisplayName("锁定期内每次失败刷新窗口_持续攻击持续锁")
  void lockRefreshesOnContinuedFailures() {
    for (int i = 0; i < LoginAttemptService.MAX_FAILURES; i++) {
      service.onFailure(KEY);
    }
    // 锁定期过半再失败一次，窗口顺延——半个锁定期后仍处于锁定。
    now.updateAndGet(t -> t.plus(Duration.ofMinutes(10)));
    service.onFailure(KEY);
    now.updateAndGet(t -> t.plus(Duration.ofMinutes(10)));
    assertThat(service.isBlocked(KEY)).isTrue();
  }

  @Test
  @DisplayName("不同键互不影响")
  void keysAreIndependent() {
    for (int i = 0; i < LoginAttemptService.MAX_FAILURES; i++) {
      service.onFailure(KEY);
    }
    assertThat(service.isBlocked(KEY)).isTrue();
    assertThat(service.isBlocked("admin|10.0.0.2")).isFalse();
    assertThat(service.isBlocked("other|127.0.0.1")).isFalse();
  }
}
