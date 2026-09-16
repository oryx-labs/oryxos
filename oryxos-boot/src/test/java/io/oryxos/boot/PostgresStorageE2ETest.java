package io.oryxos.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.oryxos.cli.OryxOsRuntime;
import io.oryxos.core.provider.ProviderRegistry;
import io.oryxos.storage.LlmCall;
import io.oryxos.storage.LlmCallRepository;
import io.oryxos.storage.MemoryEntry;
import io.oryxos.storage.MemoryEntryRepository;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * 025 PG 全链路 smoke（US1，SC-002/003 测试面等价物）：嵌入式 PG 上真启动——vendor 自动适配 （SQLite 专属默认让位）、Flyway
 * postgresql 目录迁移、审计/记忆/密文往返；第二个上下文连同一库 立即可见第一个上下文的数据（共同事实源）。故障面：错误凭证拒启（SC-008 之一）。
 */
@Tag("postgres")
class PostgresStorageE2ETest {

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

  private static String pgUrl() {
    return "jdbc:postgresql://localhost:" + postgres.getPort() + "/postgres";
  }

  private static ConfigurableApplicationContext boot(Path root, String username) {
    return new SpringApplicationBuilder(OryxOsRuntime.class)
        .run(
            "--oryxos.root=" + root,
            "--oryxos.providers[0].name=mock",
            "--spring.datasource.url=" + pgUrl(),
            "--spring.datasource.username=" + username,
            "--spring.lifecycle.timeout-per-shutdown-phase=100ms",
            "--spring.main.web-application-type=none");
  }

  @Test
  @DisplayName("PG deployment: migrate, write via repositories, second context sees the data")
  void postgresIsASharedFactSource() throws Exception {
    Path root = seedWorkspace();

    try (ConfigurableApplicationContext first = boot(root, "postgres")) {
      // vendor 适配生效：真连的是 PG，不是内置 SQLite 默认
      try (Connection connection = first.getBean(DataSource.class).getConnection()) {
        assertEquals("PostgreSQL", connection.getMetaData().getDatabaseProductName());
      }
      assertFlywayHistoryHealthy();

      LlmCallRepository llmCalls = first.getBean(LlmCallRepository.class);
      LlmCall call = new LlmCall();
      call.setSessionId("pg-e2e-session");
      call.setProvider("mock");
      call.setModel("mock-model");
      call.setSuccess(true);
      call.setDurationMs(5L);
      call.setTraceId("pg-trace-0001");
      llmCalls.save(call);

      MemoryEntryRepository memories = first.getBean(MemoryEntryRepository.class);
      MemoryEntry entry = new MemoryEntry();
      entry.setAgentName(MemoryEntry.GLOBAL_AGENT);
      entry.setScope("ARCHIVAL");
      entry.setContent("pg smoke memory");
      memories.save(entry);

      // 022 密文往返：注册表写入即密文落库、读回解密一致（TEXT 列两库无缝，FR-009）
      ProviderRegistry providers = first.getBean(ProviderRegistry.class);
      providers.save(
          new io.oryxos.core.provider.ProviderDef(
              "pg-smoke", "sk-plain-secret", "https://example.com", "smoke"));
      assertEquals("sk-plain-secret", providers.find("pg-smoke").orElseThrow().apiKey());
      assertCiphertextAtRest();
    }

    // 第二个上下文连同一 PG：立即看到第一个上下文的数据——共同事实源成立（SC-003）
    try (ConfigurableApplicationContext second = boot(root, "postgres")) {
      assertEquals(
          1, second.getBean(LlmCallRepository.class).findBySessionId("pg-e2e-session").size());
      assertTrue(
          second.getBean(MemoryEntryRepository.class).findAll().stream()
              .anyMatch(m -> "pg smoke memory".equals(m.getContent())));
      assertEquals(
          "sk-plain-secret",
          second.getBean(ProviderRegistry.class).find("pg-smoke").orElseThrow().apiKey());
    }
    assertFlywayHistoryHealthy();
  }

  @Test
  @DisplayName("wrong credentials refuse startup instead of silently falling back")
  void wrongCredentialsRefuseStartup() throws Exception {
    Path root = seedWorkspace();
    assertThrows(
        Exception.class,
        () -> {
          try (ConfigurableApplicationContext ignored = boot(root, "wrong-user")) {
            // 不应到达：错误凭证必须拒启（SC-008），绝不静默降级回 SQLite
          }
        });
  }

  @Test
  @DisplayName("unreachable database url refuses startup")
  void unreachableUrlRefusesStartup() throws Exception {
    Path root = seedWorkspace();
    assertThrows(
        Exception.class,
        () -> {
          try (ConfigurableApplicationContext ignored =
              new SpringApplicationBuilder(OryxOsRuntime.class)
                  .run(
                      "--oryxos.root=" + root,
                      "--oryxos.providers[0].name=mock",
                      // 未监听端口：立即 connection refused（SC-008 连接失败类）
                      "--spring.datasource.url=jdbc:postgresql://localhost:1/oryxos",
                      "--spring.datasource.username=postgres",
                      "--spring.datasource.hikari.initialization-fail-timeout=1",
                      "--spring.main.web-application-type=none")) {
            // 不应到达：不可达库必须拒启
          }
        });
  }

  @Test
  @DisplayName("account without create privilege refuses startup at migration")
  void readOnlyAccountRefusesStartup() throws Exception {
    Path root = seedWorkspace();
    try (Connection connection = postgres.getPostgresDatabase().getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute("CREATE DATABASE limited_db");
      statement.execute("CREATE USER limited WITH PASSWORD 'limited'");
    }
    try (Connection connection =
            DriverManager.getConnection(
                "jdbc:postgresql://localhost:" + postgres.getPort() + "/limited_db?user=postgres");
        Statement statement = connection.createStatement()) {
      statement.execute("REVOKE CREATE ON SCHEMA public FROM PUBLIC");
      statement.execute("REVOKE CREATE ON SCHEMA public FROM limited");
    }
    assertThrows(
        Exception.class,
        () -> {
          try (ConfigurableApplicationContext ignored =
              new SpringApplicationBuilder(OryxOsRuntime.class)
                  .run(
                      "--oryxos.root=" + root,
                      "--oryxos.providers[0].name=mock",
                      "--spring.datasource.url="
                          + "jdbc:postgresql://localhost:"
                          + postgres.getPort()
                          + "/limited_db",
                      "--spring.datasource.username=limited",
                      "--spring.datasource.password=limited",
                      "--spring.main.web-application-type=none")) {
            // 不应到达：无建表权限必须在迁移期拒启（SC-008 权限类）
          }
        });
  }

  /**
   * postgresql 目录现为 V1 基线 + V6 Run 工作台 + V7 协调面（026）+ V8 文件面（027）+ V9 Web 用户角色（039）+ V10 OIDC
   * identity（040）+ V11 资产治理事件（041）；空库无 baseline 行——恰 7 条成功记录。
   */
  private static void assertFlywayHistoryHealthy() throws Exception {
    try (Connection connection = postgres.getPostgresDatabase().getConnection();
        Statement statement = connection.createStatement();
        ResultSet rows =
            statement.executeQuery(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success = true")) {
      assertTrue(rows.next());
      assertEquals(7, rows.getLong(1));
    }
  }

  /** 库里落的必须是 enc:v1: 密文，不是明文（宪法 VI）。 */
  private static void assertCiphertextAtRest() throws Exception {
    try (Connection connection = postgres.getPostgresDatabase().getConnection();
        Statement statement = connection.createStatement();
        ResultSet rows =
            statement.executeQuery("SELECT api_key FROM providers WHERE name = 'pg-smoke'")) {
      assertTrue(rows.next());
      String stored = rows.getString("api_key");
      assertNotNull(stored);
      assertTrue(stored.startsWith("enc:v1:"), "expected ciphertext at rest, got: " + stored);
    }
  }

  private static Path seedWorkspace() throws IOException {
    Path root = Files.createTempDirectory("oryxos-pg-smoke");
    Files.createDirectories(root.resolve("memory"));
    return root;
  }
}
