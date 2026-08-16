package io.oryxos.web.error;

/** Raised when a v1 schedule key resolves to more than one profile-owned schedule. */
public class ScheduleKeyAmbiguityException extends RuntimeException {

  public ScheduleKeyAmbiguityException(String key) {
    super("Schedule key is ambiguous across profiles: " + key);
  }
}
