package io.oryxos.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.oryxos.cli.OryxOsRuntime;
import io.oryxos.core.agent.AgentLifecycleService;
import io.oryxos.core.profile.Profile;
import io.oryxos.core.provider.ProviderDef;
import io.oryxos.core.provider.ProviderRegistry;
import io.oryxos.core.provider.ProviderRequest;
import io.oryxos.core.provider.ProviderResponse;
import io.oryxos.core.provider.ProviderService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

class ProviderDefaultSelectionTest {

  @TempDir Path root;

  @Test
  void agentDefaultsUseEffectiveRegistryWhenYamlProviderWasNotSeeded() throws Exception {
    Path workspace = Files.createDirectories(root.resolve("workspace"));
    Files.createDirectories(workspace.resolve("agents"));
    Files.createDirectories(workspace.resolve("memory"));
    String dbUrl = "jdbc:sqlite:" + root.resolve("provider-default.db");
    initializeProviderDatabase(dbUrl);

    ConfigurableApplicationContext context = boot(workspace, dbUrl);
    try {
      ProviderRegistry registry = context.getBean(ProviderRegistry.class);
      assertEquals(List.of("qwen"), registry.list().stream().map(ProviderDef::name).toList());

      AgentLifecycleService lifecycle = context.getBean(AgentLifecycleService.class);
      Profile created = lifecycle.create("created", "created from the effective registry");
      assertEquals("qwen", created.provider().name());

      lifecycle.generateDraft("generated", "generate from the effective registry", null, List.of());
      assertEquals("qwen", context.getBean(RecordingProviderService.class).lastProvider());
    } finally {
      context.getBean("workspaceWatcherExecutor", ThreadPoolTaskExecutor.class).shutdown();
      context.close();
    }
  }

  private static void initializeProviderDatabase(String dbUrl) throws SQLException {
    try (var connection = DriverManager.getConnection(dbUrl);
        var statement = connection.createStatement()) {
      statement.execute(
          """
          CREATE TABLE providers (
              name VARCHAR(128) PRIMARY KEY,
              api_key TEXT,
              base_url TEXT,
              description TEXT,
              created_at TIMESTAMP NOT NULL,
              updated_at TIMESTAMP NOT NULL
          )
          """);
      statement.execute(
          """
          INSERT INTO providers
              (name, api_key, base_url, description, created_at, updated_at)
          VALUES
              ('qwen', 'db-key', 'https://db.example/v1', 'database-managed',
               CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
          """);
    }
  }

  private static ConfigurableApplicationContext boot(Path workspace, String dbUrl) {
    return new SpringApplicationBuilder(OryxOsRuntime.class, RecordingProviderConfiguration.class)
        .web(WebApplicationType.NONE)
        .run(
            "--spring.main.banner-mode=off",
            "--oryxos.root=" + workspace,
            "--spring.datasource.url=" + dbUrl,
            "--oryxos.providers[0].name=deepseek",
            "--oryxos.providers[0].api-key=",
            "--oryxos.providers[0].base-url=https://yaml.example/v1",
            "--oryxos.author.provider=",
            "--oryxos.author.model=author-model");
  }

  @TestConfiguration
  static class RecordingProviderConfiguration {

    @Bean
    @Primary
    RecordingProviderService recordingProviderService() {
      return new RecordingProviderService();
    }
  }

  static final class RecordingProviderService implements ProviderService {

    private final AtomicReference<String> lastProvider = new AtomicReference<>();

    @Override
    public ProviderResponse chat(String sessionId, Profile profile, ProviderRequest request) {
      lastProvider.set(profile.provider().name());
      String generated =
          """
          ---
          name: generated
          description: generated agent
          provider:
            name: qwen
            model: qwen-model
          ---

          Generated instructions.
          """;
      return new ProviderResponse(generated, List.of(), null);
    }

    String lastProvider() {
      return lastProvider.get();
    }
  }
}
