package io.oryxos.storage;

import io.oryxos.core.provider.ProviderDef;
import io.oryxos.core.provider.ProviderRegistry;
import java.util.List;
import java.util.Optional;

/** {@link ProviderRegistry} 的 SQLite/JPA 实现：providers 表 ↔ {@link ProviderDef} 互转。 */
public class JpaProviderRegistry implements ProviderRegistry {

  private final LlmProviderRepository repository;

  public JpaProviderRegistry(LlmProviderRepository repository) {
    this.repository = repository;
  }

  @Override
  public List<ProviderDef> list() {
    return repository.findAll().stream().map(JpaProviderRegistry::toDef).toList();
  }

  @Override
  public Optional<ProviderDef> find(String name) {
    return repository.findById(name).map(JpaProviderRegistry::toDef);
  }

  @Override
  public boolean exists(String name) {
    return repository.existsById(name);
  }

  @Override
  public ProviderDef save(ProviderDef provider) {
    LlmProvider entity = repository.findById(provider.name()).orElseGet(LlmProvider::new);
    entity.setName(provider.name());
    entity.setApiKey(provider.apiKey());
    entity.setBaseUrl(provider.baseUrl());
    entity.setDescription(provider.description());
    return toDef(repository.save(entity));
  }

  @Override
  public void delete(String name) {
    repository.deleteById(name);
  }

  private static ProviderDef toDef(LlmProvider e) {
    return new ProviderDef(e.getName(), e.getApiKey(), e.getBaseUrl(), e.getDescription());
  }
}
