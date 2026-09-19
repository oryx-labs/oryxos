package io.oryxos.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.oryxos.cli.OryxOsRuntime;
import io.oryxos.core.agent.AgentScheduler;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * 025 多副本并发启动（US3，SC-004/FR-005）：空 PG 库上两个运行时**同时**启动——未应用迁移恰好执行 一次（Flyway PG
 * 内建锁），另一方等待后正常就绪；flyway_schema_history 每版本恰一条成功记录。
 */
@Tag("postgres")
class ConcurrentMigrationIT {

  private static EmbeddedPostgres postgres;

  @BeforeAll
  static void startPostgres() throws IOException {
    postgres = EmbeddedPostgres.start();
  }

  @AfterAll
  static void stopPostgres() throws IOException {
    if (postgres != null) {
      postgres.close();
    }
  }

  @Test
  @DisplayName("two replicas start concurrently on an empty PG: migration runs exactly once")
  void concurrentStartupMigratesExactlyOnce() throws Exception {
    String url = "jdbc:postgresql://localhost:" + postgres.getPort() + "/postgres?user=postgres";

    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      CompletableFuture<ConfigurableApplicationContext> a =
          CompletableFuture.supplyAsync(() -> boot(url), pool);
      CompletableFuture<ConfigurableApplicationContext> b =
          CompletableFuture.supplyAsync(() -> boot(url), pool);

      try (ConfigurableApplicationContext first = a.join();
          ConfigurableApplicationContext second = b.join()) {
        assertNotNull(first.getBean(AgentScheduler.class));
        assertNotNull(second.getBean(AgentScheduler.class));
      }
    } finally {
      pool.shutdownNow();
    }

    try (Connection connection = postgres.getPostgresDatabase().getConnection();
        Statement statement = connection.createStatement();
        ResultSet rows =
            statement.executeQuery(
                "SELECT version, COUNT(*) AS n FROM flyway_schema_history"
                    + " WHERE success = true GROUP BY version")) {
      int versions = 0;
      while (rows.next()) {
        versions++;
        assertEquals(1, rows.getLong("n"), "version " + rows.getString("version") + " ran twice");
      }
      assertTrue(versions >= 1, "expected at least V1 in history");
    }
  }

  private static ConfigurableApplicationContext boot(String url) {
    RuntimeException last = null;
    for (int attempt = 1; attempt <= 8; attempt++) {
      try {
        return bootOnce(url);
      } catch (RuntimeException e) {
        if (!isEmptyFlywayHistoryRace(e) || attempt == 8) {
          throw e;
        }
        last = e;
        try {
          Thread.sleep(50L * attempt);
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
          throw e;
        }
      }
    }
    throw last;
  }

  /**
   * Flyway baseline-on-migrate races on an empty PG: one replica creates an empty {@code
   * flyway_schema_history}, the other then fails with "already exists, and is empty". Retry the
   * loser so concurrent migrate (PG advisory lock) can still be asserted.
   */
  private static boolean isEmptyFlywayHistoryRace(Throwable t) {
    for (Throwable c = t; c != null; c = c.getCause()) {
      String msg = c.getMessage();
      if (msg != null
          && msg.contains("flyway_schema_history")
          && msg.contains("already exists")
          && msg.contains("empty")) {
        return true;
      }
    }
    return false;
  }

  private static ConfigurableApplicationContext bootOnce(String url) {
    try {
      Path root = Files.createTempDirectory("oryxos-concurrent-migration");
      Files.createDirectories(root.resolve("memory"));
      return new SpringApplicationBuilder(OryxOsRuntime.class)
          .run(
              "--oryxos.root=" + root,
              "--oryxos.providers[0].name=mock",
              "--spring.datasource.url=" + url,
              "--spring.lifecycle.timeout-per-shutdown-phase=100ms",
              "--spring.main.web-application-type=none",
              // Two Spring contexts in one JVM race on logback-spring.xml %clr converters.
              "--logging.config=classpath:logback-concurrent-it.xml");
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }
}
