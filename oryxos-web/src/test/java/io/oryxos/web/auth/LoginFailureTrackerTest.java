package io.oryxos.web.auth;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class LoginFailureTrackerTest {

  @Test
  void locksOnFifthFailureAndClearsAfterSuccessfulAuthentication() {
    MutableClock clock = new MutableClock(Instant.parse("2026-07-22T09:00:00Z"));
    LoginFailureTracker tracker = new LoginFailureTracker(clock);

    assertFalse(tracker.recordFailure("admin").locked());
    assertFalse(tracker.recordFailure("admin").locked());
    assertFalse(tracker.recordFailure("admin").locked());
    assertFalse(tracker.recordFailure("admin").locked());

    assertTrue(tracker.recordFailure("admin").locked());
    assertTrue(tracker.current("admin").locked());

    tracker.clear("admin");

    assertFalse(tracker.current("admin").locked());
  }

  @Test
  void lockExpiresAfterFifteenMinutesAndNewAttemptStartsFreshWindow() {
    MutableClock clock = new MutableClock(Instant.parse("2026-07-22T09:00:00Z"));
    LoginFailureTracker tracker = new LoginFailureTracker(clock);

    for (int i = 0; i < 5; i++) {
      tracker.recordFailure("ghost");
    }
    assertTrue(tracker.current("ghost").locked());

    clock.advance(Duration.ofMinutes(16));

    assertFalse(tracker.current("ghost").locked());
    assertFalse(tracker.recordFailure("ghost").locked());
  }

  private static final class MutableClock extends Clock {
    private Instant now;

    private MutableClock(Instant now) {
      this.now = now;
    }

    void advance(Duration duration) {
      now = now.plus(duration);
    }

    @Override
    public ZoneOffset getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId zone) {
      return Clock.fixed(now, zone);
    }

    @Override
    public Instant instant() {
      return now;
    }
  }
}
