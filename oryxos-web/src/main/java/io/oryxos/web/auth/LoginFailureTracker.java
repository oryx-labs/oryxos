package io.oryxos.web.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Tracks temporary login lockouts for submitted administrator usernames. */
public class LoginFailureTracker {

  private static final int MAX_FAILURES = 5;
  private static final Duration WINDOW = Duration.ofMinutes(15);
  private static final Duration LOCK_DURATION = Duration.ofMinutes(15);

  private final Clock clock;
  private final Map<String, State> states = new ConcurrentHashMap<>();

  public LoginFailureTracker(Clock clock) {
    this.clock = clock;
  }

  public Result current(String username) {
    String key = key(username);
    State state = states.get(key);
    if (state == null) {
      return Result.open();
    }
    synchronized (state) {
      if (state.lockedUntil != null && !clock.instant().isBefore(state.lockedUntil)) {
        states.remove(key, state);
        return Result.open();
      }
      return state.lockedUntil == null ? Result.open() : Result.locked(state.lockedUntil);
    }
  }

  public Result recordFailure(String username) {
    String key = key(username);
    State state = states.computeIfAbsent(key, ignored -> new State(clock.instant()));
    synchronized (state) {
      Instant now = clock.instant();
      if (state.lockedUntil != null && now.isBefore(state.lockedUntil)) {
        return Result.locked(state.lockedUntil);
      }
      if (state.lockedUntil != null
          || Duration.between(state.windowStartedAt, now).compareTo(WINDOW) > 0) {
        state.windowStartedAt = now;
        state.failureCount = 0;
        state.lockedUntil = null;
      }
      state.failureCount++;
      if (state.failureCount >= MAX_FAILURES) {
        state.lockedUntil = now.plus(LOCK_DURATION);
        return Result.locked(state.lockedUntil);
      }
      return Result.open();
    }
  }

  public void clear(String username) {
    states.remove(key(username));
  }

  private static String key(String username) {
    return username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
  }

  public static final class Result {
    private final boolean locked;
    private final Instant lockedUntil;

    private Result(boolean locked, Instant lockedUntil) {
      this.locked = locked;
      this.lockedUntil = lockedUntil;
    }

    public boolean locked() {
      return locked;
    }

    public Instant lockedUntil() {
      return lockedUntil;
    }

    private static Result open() {
      return new Result(false, null);
    }

    private static Result locked(Instant lockedUntil) {
      return new Result(true, lockedUntil);
    }
  }

  private static final class State {
    private Instant windowStartedAt;
    private int failureCount;
    private Instant lockedUntil;

    private State(Instant windowStartedAt) {
      this.windowStartedAt = windowStartedAt;
    }
  }
}
