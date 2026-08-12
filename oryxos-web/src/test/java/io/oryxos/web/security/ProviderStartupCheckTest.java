package io.oryxos.web.security;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import io.oryxos.core.provider.ProviderRegistry;
import io.oryxos.provider.ProviderRegistryValidator;
import org.junit.jupiter.api.Test;

class ProviderStartupCheckTest {

  @Test
  void validatesEffectiveRegistry() {
    ProviderRegistry registry = mock(ProviderRegistry.class);
    ProviderRegistryValidator validator = mock(ProviderRegistryValidator.class);

    new ProviderStartupCheck(registry, validator).afterSingletonsInstantiated();

    verify(validator).validate(registry);
  }
}
