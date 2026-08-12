package io.oryxos.provider;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.oryxos.core.provider.ProviderDef;
import io.oryxos.core.provider.ProviderRegistry;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ProviderRegistryValidatorTest {

  private ProviderRegistry registry;
  private ProviderRegistryValidator validator;

  @BeforeEach
  void setUp() {
    registry = mock(ProviderRegistry.class);
    validator = new ProviderRegistryValidator();
  }

  @Test
  void validDatabaseProvider_passesWithoutReadingYaml() {
    when(registry.list())
        .thenReturn(List.of(new ProviderDef("deepseek", "db-key", "https://db.example/v1", null)));

    assertDoesNotThrow(() -> validator.validate(registry));
  }

  @Test
  void mockProvider_allowsMissingKeyAndBaseUrl() {
    when(registry.list()).thenReturn(List.of(new ProviderDef("mock", null, null, null)));

    assertDoesNotThrow(() -> validator.validate(registry));
  }

  @Test
  void emptyRegistry_failsClearly() {
    when(registry.list()).thenReturn(List.of());

    IllegalStateException error =
        assertThrows(IllegalStateException.class, () -> validator.validate(registry));
    assertTrue(error.getMessage().contains("Provider"));
  }

  @Test
  void blankKey_failsWithoutLeakingOtherCredentialValues() {
    String secret = "must-not-leak";
    when(registry.list())
        .thenReturn(List.of(new ProviderDef("broken", " ", "https://broken.example/v1", secret)));

    IllegalStateException error =
        assertThrows(IllegalStateException.class, () -> validator.validate(registry));
    assertTrue(error.getMessage().contains("broken"));
    assertTrue(error.getMessage().contains("api-key"));
    assertFalse(error.getMessage().contains(secret));
  }

  @Test
  void blankBaseUrl_failsAndNamesProvider() {
    when(registry.list()).thenReturn(List.of(new ProviderDef("broken", "db-key", " ", null)));

    IllegalStateException error =
        assertThrows(IllegalStateException.class, () -> validator.validate(registry));
    assertTrue(error.getMessage().contains("broken"));
    assertTrue(error.getMessage().contains("base-url"));
  }
}
