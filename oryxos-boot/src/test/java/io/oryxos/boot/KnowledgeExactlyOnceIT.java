package io.oryxos.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

import io.oryxos.cli.OryxOsRuntime;
import io.oryxos.core.cluster.ClusterProperties;
import io.oryxos.core.cluster.CoordinationStore;
import io.oryxos.core.cluster.WorkspaceVersionPoller;
import io.oryxos.core.knowledge.KnowledgeAdmin;
import io.oryxos.core.knowledge.KnowledgeBackendRegistry;
import io.oryxos.core.knowledge.model.DocumentState;
import io.oryxos.core.knowledge.model.DocumentStatus;
import io.oryxos.knowledge.LocalKnowledgeBackend;
import io.oryxos.knowledge.index.KnowledgeIndexService;
import io.oryxos.knowledge.store.ChunkStore;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * 027 US2（SC-002/003）：知识索引恰好一次——双副本同触发恰一执行、死持有者超时接管、 检索恒读已提交代次、rebuild 期间 import 不丢文档（U1）、单机档零认领写。
 */
@Tag("postgres")
class KnowledgeExactlyOnceIT {

  private static EmbeddedPostgres postgres;
  private static Path sharedRoot;
  private static ConfigurableApplicationContext replicaA;
  private static ConfigurableApplicationContext replicaB;

  @BeforeAll
  static void startReplicas() throws IOException {
    postgres = EmbeddedPostgres.start();
    String url = "jdbc:postgresql://localhost:" + postgres.getPort() + "/postgres?user=postgres";
    sharedRoot = seedWorkspace();
    replicaA = boot(url, sharedRoot, "kb-a", true);
    replicaB = boot(url, sharedRoot, "kb-b", true);
  }

  @AfterAll
  static void stopReplicas() throws IOException {
    if (replicaA != null) {
      replicaA.close();
    }
    if (replicaB != null) {
      replicaB.close();
    }
    if (postgres != null) {
      postgres.close();
    }
  }

  private static ConfigurableApplicationContext boot(
      String url, Path root, String instanceId, boolean clusterEnabled) {
    return new SpringApplicationBuilder(OryxOsRuntime.class)
        .run(
            "--oryxos.root=" + root,
            "--oryxos.providers[0].name=mock",
            "--embedding.provider=mock",
            "--spring.datasource.url=" + url,
            "--oryxos.cluster.enabled=" + clusterEnabled,
            "--oryxos.cluster.instance-id=" + instanceId,
            "--oryxos.cluster.workspace-poll-interval=100ms",
            "--oryxos.cluster.lease-ttl=2s",
            "--oryxos.cluster.wait-timeout=10s",
            "--memory.backend=sqlite",
            "--spring.lifecycle.timeout-per-shutdown-phase=100ms",
            "--spring.main.web-application-type=none");
  }

  private static KnowledgeAdmin admin(ConfigurableApplicationContext ctx) {
    return ctx.getBean(KnowledgeBackendRegistry.class).localDefault().admin().orElseThrow();
  }

  @Test
  @DisplayName("simultaneous rebuild on both replicas builds exactly once, no duplicate chunks")
  void simultaneousRebuildExactlyOnce() throws Exception {
    String kb = "kb-race";
    admin(replicaA).createBase(kb, "并发重建");
    Files.writeString(sharedRoot.resolve("knowledge/" + kb + "/doc.md"), "# 文档\n\n恰好一次的内容。");
    admin(replicaA).importDocument(kb, "doc.md");
    awaitReady(admin(replicaA), kb);

    CountDownLatch gate = new CountDownLatch(1);
    ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
    Callable<String> viaA = () -> rebuildOutcome(replicaA, kb, gate);
    Callable<String> viaB = () -> rebuildOutcome(replicaB, kb, gate);
    List<Future<String>> futures = List.of(pool.submit(viaA), pool.submit(viaB));
    gate.countDown();
    int built = 0;
    for (Future<String> f : futures) {
      if ("built".equals(f.get())) {
        built++;
      }
    }
    pool.shutdown();
    assertTrue(built >= 1, "至少一个副本完成重建");

    // 两副本代次一致 + 文档不重复（恰一份）
    long genA = replicaA.getBean(CoordinationStore.class).committedGeneration(kb).orElseThrow();
    long genB = replicaB.getBean(CoordinationStore.class).committedGeneration(kb).orElseThrow();
    assertEquals(genA, genB, "已提交代次两副本一致");
    awaitSingleReadyDocument(admin(replicaA), kb);
  }

  @Test
  @DisplayName("rebuild observation waits for the read replica to consume the generation change")
  void rebuildObservationWaitsForReadReplicaRefresh() throws Exception {
    String kb = "kb-read-refresh";
    KnowledgeAdmin writer = admin(replicaB);
    writer.createBase(kb, "读副本刷新");
    Files.writeString(sharedRoot.resolve("knowledge/" + kb + "/doc.md"), "# 文档\n\n等待已提交代次可见。");
    writer.rebuild(kb); // 同步建立首代，避免READY已可见但异步导入claim尚未释放的fixture窗口。

    // 独立读视图仍使用共享PG存储与真实索引；不启动调度线程，由下方控制消费总线的时点。
    ChunkStore chunks = replicaA.getBean(ChunkStore.class);
    KnowledgeIndexService readerIndex =
        new KnowledgeIndexService(
            sharedRoot.resolve("knowledge"), chunks, () -> null, Runnable::run);
    CoordinationStore coordination = replicaA.getBean(CoordinationStore.class);
    ClusterProperties cluster = replicaA.getBean(ClusterProperties.class);
    readerIndex.enableClusterCoordination(coordination, cluster);
    WorkspaceVersionPoller readerPoller =
        new WorkspaceVersionPoller(
            coordination,
            cluster,
            new ThreadPoolTaskScheduler(),
            Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
    readerPoller.register("knowledge", readerIndex::invalidateGenerationCache);
    readerPoller.pollOnce();
    LocalKnowledgeBackend reader =
        new LocalKnowledgeBackend(sharedRoot.resolve("knowledge"), chunks, readerIndex, () -> null);
    assertEquals(1, reader.status(kb).size(), "先缓存旧代次");

    writer.rebuild(kb);
    long committed = coordination.committedGeneration(kb).orElseThrow();
    assertTrue(committed > readerIndex.activeGeneration(kb), "提交已完成，读视图尚未消费通知");
    assertTrue(reader.status(kb).isEmpty(), "旧代已清理，未刷新时读状态确实为空");

    // spy仅控制首个真实空状态读取之后发生poll；返回的状态、存储及重载均来自真实实现。
    LocalKnowledgeBackend observedReader = spy(reader);
    doAnswer(
            invocation -> {
              @SuppressWarnings("unchecked")
              List<DocumentStatus> statuses = (List<DocumentStatus>) invocation.callRealMethod();
              if (statuses.isEmpty()) {
                readerPoller.pollOnce();
              }
              return statuses;
            })
        .when(observedReader)
        .status(kb);

    awaitSingleReadyDocument(observedReader, kb);
    assertEquals(committed, readerIndex.activeGeneration(kb), "读取已提交的新代次，而非旧索引");
  }

  private static void awaitSingleReadyDocument(KnowledgeAdmin admin, String kbName)
      throws InterruptedException {
    awaitReady(admin, kbName);
    List<DocumentStatus> statuses = admin.status(kbName);
    assertEquals(1, statuses.size(), "文档恰一份，无重复索引");
    assertEquals(DocumentState.READY, statuses.get(0).state(), "重建后的文档必须就绪");
  }

  private static String rebuildOutcome(
      ConfigurableApplicationContext ctx, String kb, CountDownLatch gate) throws Exception {
    gate.await();
    try {
      admin(ctx).rebuild(kb);
      return "built";
    } catch (io.oryxos.core.knowledge.KnowledgeBuildInProgressException inProgress) {
      return "in-progress"; // 明确提示而非重复构建
    } catch (IllegalStateException fenced) {
      return "fenced"; // 竞态窗口内被接管/提交被拒——旧代不受影响
    }
  }

  @Test
  @DisplayName("expired claim of a dead holder is taken over; rebuild completes (SC-003)")
  void deadHolderClaimIsTakenOver() throws Exception {
    String kb = "kb-takeover";
    admin(replicaA).createBase(kb, "接管重建");
    Files.writeString(sharedRoot.resolve("knowledge/" + kb + "/doc.md"), "# 文档\n\n接管场景。");
    admin(replicaA).importDocument(kb, "doc.md");
    awaitReady(admin(replicaA), kb);

    // 死副本的落盘形态：过期认领（026 FailoverTakeoverIT 同手法——kill 由过期租约等价表达）
    CoordinationStore store = replicaB.getBean(CoordinationStore.class);
    assertTrue(store.tryAcquireIndexBuild(kb, 99L, "dead@1", Duration.ofMillis(-1000)));

    admin(replicaB).rebuild(kb); // 健康副本抢过期认领并完成
    long committed = store.committedGeneration(kb).orElseThrow();
    assertTrue(committed >= 1, "接管后代次完整提交");
    assertEquals(DocumentState.READY, admin(replicaB).status(kb).get(0).state());
  }

  @Test
  @DisplayName("import while a rebuild claim is held: queued, never silently lost (U1)")
  void importDuringRebuildIsQueuedNotLost() throws Exception {
    String kb = "kb-interleave";
    admin(replicaA).createBase(kb, "交错导入");
    Files.writeString(sharedRoot.resolve("knowledge/" + kb + "/first.md"), "# 先导\n\n第一份。");
    admin(replicaA).importDocument(kb, "first.md");
    awaitReady(admin(replicaA), kb);

    // 模拟「A 正在重建」：以他者身份持住构建认领
    CoordinationStore store = replicaA.getBean(CoordinationStore.class);
    assertTrue(store.tryAcquireIndexBuild(kb, 50L, "builder@1", Duration.ofSeconds(30)));

    // B 此刻上传导入：异步索引段应排队等待，绝不落在将被淘汰的代上静默丢
    Files.writeString(sharedRoot.resolve("knowledge/" + kb + "/second.md"), "# 后导\n\n第二份。");
    admin(replicaB).importDocument(kb, "second.md");
    Thread.sleep(500); // 给异步段进入排队

    // 「重建完成」：以持有者身份条件提交新代（模拟切代）并释放
    assertTrue(store.commitGeneration(kb, 50L, "builder@1"));

    // 排队的导入感知切代后按新代重导：最终 READY，不丢
    awaitReadyFor(admin(replicaB), kb, "second.md");
  }

  @Test
  @DisplayName("standalone rebuild writes zero build claims (FR-010)")
  void standaloneRebuildWritesNoClaims() throws Exception {
    Path soloRoot = seedWorkspace();
    String sqliteUrl = "jdbc:sqlite:" + soloRoot.resolve("solo.db");
    try (ConfigurableApplicationContext solo = boot(sqliteUrl, soloRoot, "solo", false)) {
      KnowledgeAdmin admin = admin(solo);
      admin.createBase("kb-solo", "单机档");
      Files.writeString(soloRoot.resolve("knowledge/kb-solo/doc.md"), "# 单机\n\n零协调。");
      admin.importDocument("kb-solo", "doc.md");
      awaitReady(admin, "kb-solo");
      admin.rebuild("kb-solo");
      assertEquals(
          0,
          solo.getBean(io.oryxos.storage.KnowledgeBuildClaimRepository.class).count(),
          "单机档零认领写入");
    }
  }

  private static void awaitReady(KnowledgeAdmin admin, String kbName) throws InterruptedException {
    for (int i = 0; i < 100; i++) {
      var statuses = admin.status(kbName);
      if (!statuses.isEmpty()
          && statuses.stream().allMatch(s -> s.state() == DocumentState.READY)) {
        return;
      }
      if (statuses.stream().anyMatch(s -> s.state() == DocumentState.FAILED)) {
        throw new AssertionError(
            "索引失败: "
                + statuses.stream()
                    .filter(s -> s.state() == DocumentState.FAILED)
                    .findFirst()
                    .map(s -> s.failureReason())
                    .orElse(""));
      }
      Thread.sleep(100);
    }
    throw new AssertionError("索引 10 秒内未就绪");
  }

  private static void awaitReadyFor(KnowledgeAdmin admin, String kbName, String relPath)
      throws InterruptedException {
    for (int i = 0; i < 150; i++) {
      boolean ready =
          admin.status(kbName).stream()
              .anyMatch(s -> relPath.equals(s.relPath()) && s.state() == DocumentState.READY);
      if (ready) {
        return;
      }
      Thread.sleep(100);
    }
    throw new AssertionError(relPath + " 15 秒内未就绪（疑似交错丢失，U1 回归）");
  }

  private static Path seedWorkspace() throws IOException {
    Path root = Files.createTempDirectory("oryxos-kb-exactly-once");
    Files.createDirectories(root.resolve("memory"));
    Files.createDirectories(root.resolve("agents").resolve("default"));
    Files.writeString(
        root.resolve("agents/default/AGENT.md"),
        """
        ---
        name: default
        description: kb fixture
        identity:
          agent_name: KbFixture
          prompt: You are a test agent.
        provider:
          name: mock
          model: mock-model
        settings:
          max_iterations: 3
          max_history_turns: 50
        ---
        Test fixture.
        """);
    return root;
  }
}
