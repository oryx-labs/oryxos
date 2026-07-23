package io.oryxos.core.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.oryxos.core.profile.Profile;
import io.oryxos.core.profile.ProfileRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentSkillCoordinatorTest {

  private static final String AGENT_NAME = "ops-agent";
  private static final Duration TIMEOUT = Duration.ofSeconds(3);

  @TempDir Path tempDir;

  @Test
  void fairLockOrdersActiveReaderThenQueuedWriterThenLateReader() throws Exception {
    AgentSkillLockRegistry locks = new AgentSkillLockRegistry();
    CountDownLatch readerEntered = new CountDownLatch(1);
    CountDownLatch releaseReader = new CountDownLatch(1);
    List<String> order = java.util.Collections.synchronizedList(new ArrayList<>());
    AtomicReference<Throwable> failure = new AtomicReference<>();

    Thread readerA =
        thread(
            "reader-a",
            failure,
            () ->
                locks.withReadLock(
                    AGENT_NAME,
                    () -> {
                      order.add("reader-a");
                      readerEntered.countDown();
                      assertTrue(releaseReader.await(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));
                      return null;
                    }));
    readerA.start();
    assertTrue(readerEntered.await(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));

    Thread writer =
        thread(
            "writer",
            failure,
            () ->
                locks.withWriteLock(
                    AGENT_NAME,
                    () -> {
                      order.add("writer");
                      return null;
                    }));
    writer.start();
    awaitQueued(locks, AGENT_NAME, writer);

    Thread readerC =
        thread(
            "reader-c",
            failure,
            () ->
                locks.withReadLock(
                    AGENT_NAME,
                    () -> {
                      order.add("reader-c");
                      return null;
                    }));
    readerC.start();
    awaitQueued(locks, AGENT_NAME, readerC);

    releaseReader.countDown();
    join(readerA);
    join(writer);
    join(readerC);

    assertEquals(List.of("reader-a", "writer", "reader-c"), order);
    assertNull(failure.get());
  }

  @Test
  void lockKeyIsCaseInsensitiveAndRegistryNeverDropsEntries() throws Exception {
    AgentSkillLockRegistry locks = new AgentSkillLockRegistry();

    locks.withReadLock("Ops_Agent", () -> null);
    locks.withWriteLock("ops_agent", () -> null);

    assertEquals(1, locks.registeredLockCount());
  }

  @Test
  void checkedFailureAlwaysReleasesTheLock() throws Exception {
    AgentSkillLockRegistry locks = new AgentSkillLockRegistry();

    IOException error =
        assertThrows(
            IOException.class,
            () ->
                locks.withReadLock(
                    AGENT_NAME,
                    () -> {
                      throw new IOException("boom");
                    }));
    AtomicBoolean writerRan = new AtomicBoolean();
    locks.withWriteLock(
        AGENT_NAME,
        () -> {
          writerRan.set(true);
          return null;
        });

    assertEquals("boom", error.getMessage());
    assertTrue(writerRan.get());
  }

  @Test
  void requestBuildsOneSnapshotAndLeaseCloseIsIdempotent() throws Exception {
    Fixture fixture = fixture();
    SkillSnapshot snapshot = SkillSnapshot.empty(AGENT_NAME);
    when(fixture.catalog().snapshot(AGENT_NAME)).thenReturn(snapshot);

    SkillLease lease = fixture.coordinator().openRequest(AGENT_NAME);

    assertSame(snapshot, lease.snapshot());
    assertSame(snapshot, lease.snapshot());
    verify(fixture.catalog(), times(1)).snapshot(AGENT_NAME);

    AtomicBoolean writerRan = new AtomicBoolean();
    Thread writer =
        thread(
            "writer",
            new AtomicReference<>(),
            () ->
                fixture
                    .locks()
                    .withWriteLock(
                        AGENT_NAME,
                        () -> {
                          writerRan.set(true);
                          return null;
                        }));
    writer.start();
    awaitQueued(fixture.locks(), AGENT_NAME, writer);
    assertFalse(writerRan.get());

    lease.close();
    lease.close();
    join(writer);

    assertTrue(writerRan.get());
  }

  @Test
  void snapshotFailureAndMissingAgentReleaseReadLock() throws Exception {
    Fixture fixture = fixture();
    when(fixture.catalog().snapshot(AGENT_NAME))
        .thenThrow(new IllegalStateException("scan failed"));

    assertThrows(IllegalStateException.class, () -> fixture.coordinator().openRequest(AGENT_NAME));
    assertEquals(1, fixture.locks().registeredLockCount());
    assertThrows(
        IllegalStateException.class, () -> fixture.coordinator().openRequest("missing-agent"));
    assertEquals(1, fixture.locks().registeredLockCount());

    AtomicBoolean writerRan = new AtomicBoolean();
    fixture
        .locks()
        .withWriteLock(
            AGENT_NAME,
            () -> {
              writerRan.set(true);
              return null;
            });
    assertTrue(writerRan.get());
  }

  @Test
  void linkedAgentsRootIsRejectedBeforeCatalogSnapshotAndTheReadLockIsReleased() throws Exception {
    Path outsideAgents = Files.createDirectories(tempDir.resolve("outside-agents"));
    Files.createDirectory(outsideAgents.resolve(AGENT_NAME));
    Path linkedAgents = Files.createSymbolicLink(tempDir.resolve("linked-agents"), outsideAgents);
    ProfileRegistry profiles = new ProfileRegistry(Map.of(AGENT_NAME, profile(AGENT_NAME)));
    AgentSkillCatalog catalog = mock(AgentSkillCatalog.class);
    AgentSkillLockRegistry locks = new AgentSkillLockRegistry();
    AgentSkillCoordinator coordinator =
        new AgentSkillCoordinator(linkedAgents, profiles, catalog, locks);

    assertThrows(IllegalStateException.class, () -> coordinator.openRequest(AGENT_NAME));
    verify(catalog, times(0)).snapshot(AGENT_NAME);

    AtomicBoolean writerRan = new AtomicBoolean();
    locks.withWriteLock(
        AGENT_NAME,
        () -> {
          writerRan.set(true);
          return null;
        });
    assertTrue(writerRan.get());
  }

  @Test
  void mutateUsesTheSamePerAgentWriteLock() throws Exception {
    Fixture fixture = fixture();
    when(fixture.catalog().snapshot(AGENT_NAME)).thenReturn(SkillSnapshot.empty(AGENT_NAME));
    SkillLease lease = fixture.coordinator().openRequest(AGENT_NAME);
    AtomicBoolean mutationRan = new AtomicBoolean();
    AtomicReference<Throwable> failure = new AtomicReference<>();

    Thread mutation =
        thread(
            "mutation",
            failure,
            () ->
                fixture
                    .coordinator()
                    .mutate(
                        AGENT_NAME,
                        () -> {
                          mutationRan.set(true);
                          return null;
                        }));
    mutation.start();
    awaitQueued(fixture.locks(), AGENT_NAME, mutation);
    assertFalse(mutationRan.get());

    lease.close();
    join(mutation);

    assertTrue(mutationRan.get());
    assertNull(failure.get());
  }

  private Fixture fixture() throws IOException {
    Path agentsDir = Files.createDirectories(tempDir.resolve("agents"));
    Files.createDirectory(agentsDir.resolve(AGENT_NAME));
    Profile profile = profile(AGENT_NAME);
    ProfileRegistry profiles = new ProfileRegistry(Map.of(AGENT_NAME, profile));
    AgentSkillCatalog catalog = mock(AgentSkillCatalog.class);
    AgentSkillLockRegistry locks = new AgentSkillLockRegistry();
    AgentSkillCoordinator coordinator =
        new AgentSkillCoordinator(agentsDir, profiles, catalog, locks);
    return new Fixture(catalog, locks, coordinator);
  }

  private static Profile profile(String name) {
    return new Profile(
        name,
        "test",
        null,
        new Profile.ProviderRef("mock", "mock", null),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        Profile.Settings.defaults());
  }

  private static Thread thread(
      String name, AtomicReference<Throwable> failure, ThrowingRunnable work) {
    return Thread.ofPlatform()
        .name(name)
        .unstarted(
            () -> {
              try {
                work.run();
              } catch (Throwable error) {
                failure.compareAndSet(null, error);
              }
            });
  }

  private static void awaitQueued(AgentSkillLockRegistry locks, String agentName, Thread thread) {
    long deadline = System.nanoTime() + TIMEOUT.toNanos();
    while (!locks.hasQueuedThread(agentName, thread) && System.nanoTime() < deadline) {
      Thread.onSpinWait();
    }
    assertTrue(locks.hasQueuedThread(agentName, thread), thread.getName() + " did not queue");
  }

  private static void join(Thread thread) throws InterruptedException {
    thread.join(TIMEOUT.toMillis());
    assertFalse(thread.isAlive(), thread.getName() + " did not finish");
  }

  private record Fixture(
      AgentSkillCatalog catalog, AgentSkillLockRegistry locks, AgentSkillCoordinator coordinator) {}

  @FunctionalInterface
  private interface ThrowingRunnable {
    void run() throws Exception;
  }
}
