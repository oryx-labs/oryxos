package io.oryxos.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.oryxos.core.provider.ProviderDef;
import io.oryxos.core.provider.ProviderRegistry;
import io.oryxos.provider.ProvidersProperties.ProviderConfig;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;

class ProviderRegistryBootstrapTest {

  private ProviderRegistry registry;
  private ProviderRegistryBootstrap bootstrap;

  @BeforeEach
  void setUp() {
    registry = mock(ProviderRegistry.class);
    bootstrap = new ProviderRegistryBootstrap(new ProviderRegistryValidator());
  }

  @Test
  void missingValidProvider_isSeededOnce() {
    when(registry.exists("deepseek")).thenReturn(false);
    ProvidersProperties properties =
        new ProvidersProperties(
            List.of(new ProviderConfig("deepseek", "yaml-key", "https://seed.example/v1")));

    bootstrap.seedMissing(registry, properties);

    ArgumentCaptor<ProviderDef> saved = ArgumentCaptor.forClass(ProviderDef.class);
    verify(registry).save(saved.capture());
    assertEquals("deepseek", saved.getValue().name());
    assertEquals("yaml-key", saved.getValue().apiKey());
  }

  @Test
  void existingProvider_isNeverOverwrittenEvenWhenYamlDiffers() {
    when(registry.exists("deepseek")).thenReturn(true);
    ProvidersProperties properties =
        new ProvidersProperties(
            List.of(new ProviderConfig("deepseek", "yaml-old", "https://yaml.example/v1")));

    bootstrap.seedMissing(registry, properties);

    verify(registry, never()).save(any());
  }

  @Test
  void existingProvider_isKeptWhenYamlKeyIsBlank() {
    ProviderRegistryValidator validator = mock(ProviderRegistryValidator.class);
    bootstrap = new ProviderRegistryBootstrap(validator);
    when(registry.exists("deepseek")).thenReturn(true);
    ProvidersProperties properties =
        new ProvidersProperties(
            List.of(new ProviderConfig("deepseek", "", "https://yaml.example/v1")));

    bootstrap.seedMissing(registry, properties);

    verify(validator, never()).violation(any());
    verify(registry, never()).save(any());
  }

  @Test
  void missingProviderWithBlankOrUnresolvedKey_isNotPersisted() {
    ProvidersProperties blank =
        new ProvidersProperties(
            List.of(new ProviderConfig("deepseek", " ", "https://seed.example/v1")));
    ProvidersProperties unresolved =
        new ProvidersProperties(
            List.of(new ProviderConfig("kimi", "${KIMI_API_KEY}", "https://api.moonshot.cn/v1")));

    bootstrap.seedMissing(registry, blank);
    bootstrap.seedMissing(registry, unresolved);

    verify(registry, never()).save(any());
  }

  @Test
  void invalidProvider_logReasonCannotInjectNewline() {
    ProviderRegistryValidator validator = mock(ProviderRegistryValidator.class);
    when(validator.violation(any())).thenReturn(Optional.of("invalid\r\nforged"));
    bootstrap = new ProviderRegistryBootstrap(validator);
    Logger logger = (Logger) LoggerFactory.getLogger(ProviderRegistryBootstrap.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    boolean previousAdditive = logger.isAdditive();
    appender.start();
    logger.addAppender(appender);
    logger.setAdditive(false);

    try {
      bootstrap.seedMissing(
          registry,
          new ProvidersProperties(
              List.of(new ProviderConfig("deepseek", "test-key", "https://seed.example/v1"))));
    } finally {
      logger.detachAppender(appender);
      logger.setAdditive(previousAdditive);
      appender.stop();
    }

    assertEquals(1, appender.list.size());
    assertEquals("invalid__forged", appender.list.getFirst().getArgumentArray()[1]);
    verify(registry, never()).save(any());
  }

  @Test
  void mockProvider_canSeedWithoutKeyOrBaseUrl() {
    when(registry.exists("mock")).thenReturn(false);

    bootstrap.seedMissing(
        registry, new ProvidersProperties(List.of(new ProviderConfig("mock", null, null))));

    verify(registry).save(new ProviderDef("mock", null, null, null));
  }
}
