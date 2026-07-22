package io.oryxos.core.skill;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** A request-scoped immutable Skill snapshot plus the read lock that protects its L2/L3 files. */
public final class SkillLease implements AutoCloseable {

  private final SkillSnapshot snapshot;
  private final Runnable release;
  private final AtomicBoolean closed = new AtomicBoolean();

  SkillLease(SkillSnapshot snapshot, Runnable release) {
    this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
    this.release = Objects.requireNonNull(release, "release");
  }

  public SkillSnapshot snapshot() {
    return snapshot;
  }

  /** Idempotent so nested finally/try-with-resources cleanup cannot unlock twice. */
  @Override
  public void close() {
    if (closed.compareAndSet(false, true)) {
      release.run();
    }
  }
}
