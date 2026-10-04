package io.oryxos.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.oryxos.cli.OryxOsRuntime;
import io.oryxos.core.agent.AgentExecutionStore;
import io.oryxos.core.agent.AgentService;
import io.oryxos.core.channel.ChannelLeaseCoordinator;
import io.oryxos.core.cluster.ClusterProperties;
import io.oryxos.core.cluster.CoordinationStore;
import io.oryxos.core.session.Session;
import io.oryxos.core.session.SessionManager;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * 026 US3：副本故障接管——过期租约被抢占且悬空 execution 补失败留痕（不重放）、下一条消息健康副本 正常处理、独连型渠道属主接管、实例死活可见（SC-003/005/006
 * 测试面）。fencing 写回拒绝的端到端 演练归 quickstart V5-3（SIGSTOP 真机），其单机语义由 DbTurnCoordinatorTest 钉死。
 */
@Tag("postgres")
class FailoverTakeoverIT {

  private static EmbeddedPostgres postgres;
  private static ConfigurableApplicationContext replica;
  private static String url;

  @BeforeAll
  static void start() throws IOException {
    postgres = EmbeddedPostgres.start();
    url = "jdbc:postgresql://localhost:" + postgres.getPort() + "/postgres?user=postgres";
    Path root = seedWorkspace();
    replica =
        new SpringApplicationBuilder(OryxOsRuntime.class)
            .run(
                "--oryxos.root=" + root,
                "--oryxos.providers[0].name=mock",
                "--spring.datasource.url=" + url,
                "--oryxos.cluster.enabled=true",
                "--oryxos.cluster.instance-id=survivor",
                "--oryxos.cluster.lease-ttl=2s",
                "--oryxos.cluster.poll-interval=100ms",
                "--oryxos.cluster.wait-timeout=1500ms",
                "--memory.backend=sqlite",
                "--spring.lifecycle.timeout-per-shutdown-phase=100ms",
                "--spring.main.web-application-type=none");
  }

  @AfterAll
  static void stop() throws IOException {
    if (replica != null) {
      replica.close();
    }
    if (postgres != null) {
      postgres.close();
    }
  }

  @Test
  @DisplayName("expired lease from a dead replica is reclaimed; dangling execution marked failed")
  void deadReplicaLeaseReclaimedAndExecutionMarkedFailed() throws Exception {
    SessionManager sessions = replica.getBean(SessionManager.class);
    Session session = sessions.getOrCreate("it", "failover-user", "default");
    CoordinationStore store = replica.getBean(CoordinationStore.class);
    AgentExecutionStore executions = replica.getBean(AgentExecutionStore.class);

    // 造「死副本」现场：它的租约已过期、execution 悬空运行中（等价于 kill -9 后的落盘形态）
    long danglingExecution = executions.start("default", "it-dead-replica", Instant.now());
    assertTrue(store.tryAcquireTurn(session.sessionId(), "dead@1", Duration.ofMillis(-2000)));
    store.attachExecution(session.sessionId(), "dead@1", danglingExecution);

    // 用户下一条消息落到健康副本：抢过期成功、正常处理、历史完整
    String reply = replica.getBean(AgentService.class).process(session, "副本死后的下一条消息");
    assertFalse(reply.isBlank());

    // 悬空轮已补失败留痕（不重放——只有这一条新消息的执行）
    try (Connection conn = DriverManager.getConnection(url);
        Statement st = conn.createStatement();
        ResultSet rs =
            st.executeQuery(
                "SELECT success, ended_at, error_message FROM agent_executions WHERE id = "
                    + danglingExecution)) {
      assertTrue(rs.next());
      assertFalse(rs.getBoolean("success"));
      assertNotNull(rs.getTimestamp("ended_at"));
      assertTrue(rs.getString("error_message").contains("副本失联"));
    }
  }

  @Test
  @DisplayName("waiting behind a live holder times out with the dedicated exception")
  void waitingBehindLiveHolderTimesOut() {
    SessionManager sessions = replica.getBean(SessionManager.class);
    Session session = sessions.getOrCreate("it", "busy-user", "default");
    CoordinationStore store = replica.getBean(CoordinationStore.class);
    // 另一副本正持有且未过期（存活处理中）
    assertTrue(store.tryAcquireTurn(session.sessionId(), "other@1", Duration.ofSeconds(30)));
    try {
      org.junit.jupiter.api.Assertions.assertThrows(
          io.oryxos.core.cluster.TurnWaitTimeoutException.class,
          () -> replica.getBean(AgentService.class).process(session, "排队消息"));
    } finally {
      store.releaseTurn(session.sessionId(), "other@1");
    }
  }

  @Test
  @DisplayName("exclusive channel ownership fails over to the standby coordinator")
  void exclusiveChannelOwnershipFailsOver() throws Exception {
    CoordinationStore store = replica.getBean(CoordinationStore.class);
    ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
    scheduler.initialize();
    try {
      ClusterProperties propsA = shortLeaseProps("chan-a");
      ClusterProperties propsB = shortLeaseProps("chan-b");
      ChannelLeaseCoordinator coordinatorA = new ChannelLeaseCoordinator(store, propsA, scheduler);
      ChannelLeaseCoordinator coordinatorB = new ChannelLeaseCoordinator(store, propsB, scheduler);

      AtomicBoolean connectedA = new AtomicBoolean(false);
      AtomicBoolean connectedB = new AtomicBoolean(false);
      AtomicInteger startsB = new AtomicInteger();

      coordinatorA.manage(
          "wecom-main", () -> connectedA.set(true), loss -> connectedA.set(false), connectedA::get);
      assertTrue(connectedA.get(), "先到者成为属主并建连");

      coordinatorB.manage(
          "wecom-main",
          () -> {
            connectedB.set(true);
            startsB.incrementAndGet();
          },
          loss -> connectedB.set(false),
          connectedB::get);
      assertFalse(connectedB.get(), "属主存活时待机方不建连（不互踢）");

      coordinatorA.unmanage("wecom-main"); // 属主下线（释放租约=干净退出；崩溃则等过期，语义同）
      long deadline = System.currentTimeMillis() + 10_000;
      while (!connectedB.get() && System.currentTimeMillis() < deadline) {
        Thread.sleep(100);
      }
      assertTrue(connectedB.get(), "待机方在接管窗口内建连恢复");
      assertEquals(1, startsB.get(), "恰好接管一次，无互踢循环");
      coordinatorB.unmanage("wecom-main");
    } finally {
      scheduler.shutdown();
    }
  }

  @Test
  @DisplayName("instance liveness is visible and dead instances age out")
  void instanceLivenessVisible() {
    CoordinationStore store = replica.getBean(CoordinationStore.class);
    store.heartbeat("ghost-replica", 1L);
    assertTrue(
        store.listInstances().stream().anyMatch(i -> "ghost-replica".equals(i.instanceId())));
    // 存活副本（本上下文心跳循环）也在
    assertTrue(store.listInstances().stream().anyMatch(i -> "survivor".equals(i.instanceId())));
    // 清理死行（幽灵副本无后续心跳）：负 TTL 立即判超龄
    store.purgeExpired(Duration.ofHours(12), Duration.ofSeconds(-1));
    assertFalse(
        store.listInstances().stream().anyMatch(i -> "ghost-replica".equals(i.instanceId())));
  }

  private static ClusterProperties shortLeaseProps(String instanceId) {
    ClusterProperties props = new ClusterProperties();
    props.setEnabled(true);
    props.setInstanceId(instanceId);
    props.setLeaseTtl(Duration.ofSeconds(2));
    props.setHeartbeatInterval(Duration.ofMillis(300));
    return props;
  }

  private static Path seedWorkspace() throws IOException {
    Path root = Files.createTempDirectory("oryxos-failover");
    Files.createDirectories(root.resolve("memory"));
    Files.createDirectories(root.resolve("agents").resolve("default"));
    Files.writeString(
        root.resolve("agents/default/AGENT.md"),
        """
        ---
        name: default
        description: failover fixture
        identity:
          agent_name: Failover
          prompt: You are a test agent.
        provider:
          name: mock
          model: mock-model
        tools:
          - save_memory
        settings:
          max_iterations: 3
          max_history_turns: 20
        ---
        Test fixture.
        """);
    return root;
  }
}
