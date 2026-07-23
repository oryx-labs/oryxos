package io.oryxos.core.skill;

import io.oryxos.core.agent.AgentName;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/** Per-Agent fair read/write locks shared by runtime requests and every managed mutation path. */
public final class AgentSkillLockRegistry {

  private final ConcurrentMap<String, ReentrantReadWriteLock> locks = new ConcurrentHashMap<>();

  /** Runs work while holding the Agent read lock and preserves checked exception types. */
  public <T, E extends Exception> T withReadLock(String agentName, CheckedSupplier<T, E> work)
      throws E {
    Objects.requireNonNull(work, "work");
    Lock lock = readLock(agentName);
    lock.lock();
    try {
      return work.get();
    } finally {
      lock.unlock();
    }
  }

  /** Runs work while holding the Agent write lock and preserves checked exception types. */
  public <T, E extends Exception> T withWriteLock(String agentName, CheckedSupplier<T, E> work)
      throws E {
    Objects.requireNonNull(work, "work");
    Lock lock = writeLock(agentName);
    lock.lock();
    try {
      return work.get();
    } finally {
      lock.unlock();
    }
  }

  Lock readLock(String agentName) {
    return lockFor(agentName).readLock();
  }

  private Lock writeLock(String agentName) {
    return lockFor(agentName).writeLock();
  }

  private ReentrantReadWriteLock lockFor(String agentName) {
    String key = AgentName.parse(agentName).lockKey();
    // Entries deliberately never leave this map: removing an idle-looking lock could let an old
    // holder and a newly created lock guard the same Agent at the same time.
    return locks.computeIfAbsent(key, ignored -> new ReentrantReadWriteLock(true));
  }

  boolean hasQueuedThread(String agentName, Thread thread) {
    return lockFor(agentName).hasQueuedThread(thread);
  }

  int registeredLockCount() {
    return locks.size();
  }

  /** Checked supplier used without wrapping domain or I/O failures in generic runtime errors. */
  @FunctionalInterface
  public interface CheckedSupplier<T, E extends Exception> {
    T get() throws E;
  }
}
