package io.oryxos.cli.command;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.oryxos.core.provider.ProviderRegistry;
import io.oryxos.provider.ProviderRegistryValidator;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;

class ChatCommandTest {

  @Test
  void validatesEffectiveRegistryBeforeConversation() {
    ConfigurableApplicationContext context = mock(ConfigurableApplicationContext.class);
    ProviderRegistry registry = mock(ProviderRegistry.class);
    ProviderRegistryValidator validator = mock(ProviderRegistryValidator.class);
    when(context.getBean(ProviderRegistry.class)).thenReturn(registry);
    when(context.getBean(ProviderRegistryValidator.class)).thenReturn(validator);

    ChatCommand.validateProviderRegistry(context);

    verify(validator).validate(registry);
  }
}
