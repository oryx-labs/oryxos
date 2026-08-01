package io.oryxos.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.oryxos.cli.OryxOsRuntime;
import io.oryxos.core.provider.ProviderDef;
import io.oryxos.core.provider.ProviderRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

class ProviderConfigRestartTest {

  @TempDir Path root;

  @Test
  void databaseManagedProviderSurvivesRestartWithBlankYamlKey() throws Exception {
    Path workspace = Files.createDirectories(root.resolve("workspace"));
    Files.createDirectories(workspace.resolve("agents"));
    Files.createDirectories(workspace.resolve("memory"));
    String dbUrl = "jdbc:sqlite:" + root.resolve("provider-restart.db");

    try (ConfigurableApplicationContext first =
        boot(workspace, dbUrl, "yaml-seed-key", "https://seed.example/v1")) {
      ProviderRegistry registry = first.getBean(ProviderRegistry.class);
      registry.save(
          new ProviderDef("deepseek", "db-rotated-key", "https://db.example/v1", "managed"));
    }

    try (ConfigurableApplicationContext second =
        boot(workspace, dbUrl, "", "https://seed.example/v1")) {
      ProviderDef restored = second.getBean(ProviderRegistry.class).find("deepseek").orElseThrow();
      assertEquals("db-rotated-key", restored.apiKey());
      assertEquals("https://db.example/v1", restored.baseUrl());
      assertEquals("managed", restored.description());
    }
  }

  @Test
  void nonWebRuntimeStartsWithEmptyEffectiveRegistry() throws Exception {
    Path workspace = Files.createDirectories(root.resolve("non-web-workspace"));
    Files.createDirectories(workspace.resolve("agents"));
    Files.createDirectories(workspace.resolve("memory"));
    String dbUrl = "jdbc:sqlite:" + root.resolve("non-web.db");

    try (ConfigurableApplicationContext context =
        new SpringApplicationBuilder(OryxOsRuntime.class)
            .web(WebApplicationType.NONE)
            .run(
                "--spring.main.banner-mode=off",
                "--oryxos.root=" + workspace,
                "--spring.datasource.url=" + dbUrl,
                "--oryxos.providers[0].name=deepseek",
                "--oryxos.providers[0].api-key=",
                "--oryxos.providers[0].base-url=https://seed.example/v1")) {
      assertEquals(0, context.getBean(ProviderRegistry.class).list().size());
    }
  }

  private static ConfigurableApplicationContext boot(
      Path workspace, String dbUrl, String apiKey, String baseUrl) {
    return new SpringApplicationBuilder(OryxOsRuntime.class)
        .web(WebApplicationType.SERVLET)
        .run(
            "--server.address=127.0.0.1",
            "--server.port=0",
            "--spring.main.banner-mode=off",
            "--oryxos.root=" + workspace,
            "--spring.datasource.url=" + dbUrl,
            "--oryxos.providers[0].name=deepseek",
            "--oryxos.providers[0].api-key=" + apiKey,
            "--oryxos.providers[0].base-url=" + baseUrl);
  }
}
