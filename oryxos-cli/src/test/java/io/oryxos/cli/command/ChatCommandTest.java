package io.oryxos.cli.command;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.oryxos.core.profile.Profile;
import io.oryxos.core.profile.ProfileRegistry;
import io.oryxos.core.provider.ProviderRegistry;
import io.oryxos.provider.ProviderRegistryValidator;
import java.util.List;
import java.util.Optional;
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

  @Test
  void rejectsAnAgentThatDoesNotExist() {
    ConfigurableApplicationContext context = mock(ConfigurableApplicationContext.class);
    ProfileRegistry registry = mock(ProfileRegistry.class);
    when(context.getBean(ProfileRegistry.class)).thenReturn(registry);
    when(registry.get("does-not-exist")).thenReturn(Optional.empty());
    when(registry.all()).thenReturn(List.of());

    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class,
            () -> ChatCommand.requireProfile(context, "does-not-exist"));
    assertTrue(thrown.getMessage().contains("does-not-exist"), thrown.getMessage());
  }

  @Test
  void acceptsAnAgentThatExists() {
    ConfigurableApplicationContext context = mock(ConfigurableApplicationContext.class);
    ProfileRegistry registry = mock(ProfileRegistry.class);
    when(context.getBean(ProfileRegistry.class)).thenReturn(registry);
    when(registry.get("default")).thenReturn(Optional.of(mock(Profile.class)));

    ChatCommand.requireProfile(context, "default");
  }
}
