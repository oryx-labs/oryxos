package io.oryxos.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.oryxos.cli.OryxOsRuntime;
import io.oryxos.core.agent.AgentScheduler;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * 025 迁移演进流程（US3，FR-003/FR-011 + 中断恢复 edge case）：加新迁移自动应用且重启幂等；坏迁移 拒启且报错含版本号；「列已加但 history
 * 未记成功」的半完成落盘形态（执行中途被杀）重启后收敛成功。
 */
class MigrationEvolutionIT {

  private static final String EVOLUTION_LOCATIONS =
      "classpath:db/migration/{vendor},classpath:db/evolution/{vendor}";

  @Test
  @DisplayName("a new migration applies once, restart is idempotent")
  void newMigrationAppliesExactlyOnce() throws Exception {
    Path root = seedWorkspace();
    String dbUrl = "jdbc:sqlite:" + root.resolve("evolution.db");

    try (ConfigurableApplicationContext context = boot(root, dbUrl, EVOLUTION_LOCATIONS)) {
      assertNotNull(context.getBean(AgentScheduler.class));
    }
    assertTrue(tableExists(dbUrl, "migration_probe"), "V900 should have created migration_probe");
    long historyRows = successfulHistoryRows(dbUrl);

    try (ConfigurableApplicationContext context = boot(root, dbUrl, EVOLUTION_LOCATIONS)) {
      assertNotNull(context.getBean(AgentScheduler.class));
    }
    assertEquals(historyRows, successfulHistoryRows(dbUrl), "restart must not re-apply");
  }

  @Test
  @DisplayName("a broken migration refuses startup and names the version")
  void brokenMigrationRefusesStartupWithVersion() throws Exception {
    Path root = seedWorkspace();
    String dbUrl = "jdbc:sqlite:" + root.resolve("broken.db");

    Exception failure =
        assertThrows(
            Exception.class,
            () -> {
              try (ConfigurableApplicationContext ignored =
                  boot(
                      root,
                      dbUrl,
                      "classpath:db/migration/{vendor},classpath:db/broken/{vendor}")) {
                // 不应到达：坏迁移必须拒启（FR-011）
              }
            });
    String message = rootMessages(failure);
    assertTrue(message.contains("901"), "error should name the failing version, got: " + message);
  }

  @Test
  @DisplayName("half-applied state (column added, history row missing) converges on restart")
  void interruptedMigrationConvergesOnRestart() throws Exception {
    Path root = seedWorkspace();
    String dbUrl = "jdbc:sqlite:" + root.resolve("interrupted.db");

    try (ConfigurableApplicationContext context = boot(root, dbUrl, null)) {
      assertNotNull(context.getBean(AgentScheduler.class));
    }
    // 模拟执行中途被杀的落盘形态：最后一个迁移 V11（041 资产治理事件，JavaMigration + CREATE IF NOT
    // EXISTS）效果已在，但 history 未记成功——中断只可能发生在序列尾部，只删末版 history。
    try (Connection connection = DriverManager.getConnection(dbUrl);
        Statement statement = connection.createStatement()) {
      assertEquals(
          1, statement.executeUpdate("DELETE FROM flyway_schema_history WHERE version = '11'"));
    }

    try (ConfigurableApplicationContext context = boot(root, dbUrl, null)) {
      assertNotNull(context.getBean(AgentScheduler.class));
    }
    try (Connection connection = DriverManager.getConnection(dbUrl);
        Statement statement = connection.createStatement();
        ResultSet rows =
            statement.executeQuery(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '11' AND success = 1")) {
      assertTrue(rows.next());
      assertEquals(1, rows.getLong(1), "V11 should have converged idempotently on restart");
    }
  }

  private static ConfigurableApplicationContext boot(Path root, String dbUrl, String locations) {
    SpringApplicationBuilder builder = new SpringApplicationBuilder(OryxOsRuntime.class);
    if (locations == null) {
      return builder.run(baseArgs(root, dbUrl));
    }
    String[] base = baseArgs(root, dbUrl);
    String[] args = java.util.Arrays.copyOf(base, base.length + 1);
    args[base.length] = "--spring.flyway.locations=" + locations;
    return builder.run(args);
  }

  private static String[] baseArgs(Path root, String dbUrl) {
    return new String[] {
      "--oryxos.root=" + root,
      "--oryxos.providers[0].name=mock",
      "--spring.datasource.url=" + dbUrl,
      "--spring.lifecycle.timeout-per-shutdown-phase=100ms",
      "--spring.main.web-application-type=none"
    };
  }

  private static boolean tableExists(String dbUrl, String table) throws Exception {
    try (Connection connection = DriverManager.getConnection(dbUrl);
        Statement statement = connection.createStatement();
        ResultSet rows =
            statement.executeQuery(
                "SELECT name FROM sqlite_master WHERE type='table' AND name='" + table + "'")) {
      return rows.next();
    }
  }

  private static long successfulHistoryRows(String dbUrl) throws Exception {
    try (Connection connection = DriverManager.getConnection(dbUrl);
        Statement statement = connection.createStatement();
        ResultSet rows =
            statement.executeQuery(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success = 1")) {
      assertTrue(rows.next());
      return rows.getLong(1);
    }
  }

  private static String rootMessages(Throwable failure) {
    StringBuilder all = new StringBuilder();
    for (Throwable t = failure; t != null; t = t.getCause()) {
      all.append(t.getMessage()).append('\n');
    }
    return all.toString();
  }

  private static Path seedWorkspace() throws IOException {
    Path root = Files.createTempDirectory("oryxos-migration-evolution");
    Files.createDirectories(root.resolve("memory"));
    return root;
  }
}
