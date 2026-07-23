package io.oryxos.storage;

import io.oryxos.core.sandbox.SandboxWhitelist.Category;
import io.oryxos.core.sandbox.SandboxWhitelistStore;
import java.util.List;

/** {@link SandboxWhitelistStore} 的 SQLite/JPA 实现：sandbox_whitelist 表 ↔ {@link Entry} 互转。 */
public class JpaSandboxWhitelistStore implements SandboxWhitelistStore {

  private final SandboxWhitelistRepository repository;

  public JpaSandboxWhitelistStore(SandboxWhitelistRepository repository) {
    this.repository = repository;
  }

  @Override
  public List<Entry> loadAll() {
    return repository.findAll().stream()
        .map(r -> new Entry(Category.valueOf(r.getCategory()), r.getEntryValue()))
        .toList();
  }

  @Override
  public boolean add(Category category, String value) {
    if (repository.existsByCategoryAndEntryValue(category.name(), value)) {
      return false;
    }
    SandboxWhitelistRow row = new SandboxWhitelistRow();
    row.setCategory(category.name());
    row.setEntryValue(value);
    repository.save(row);
    return true;
  }

  @Override
  public boolean remove(Category category, String value) {
    List<SandboxWhitelistRow> rows = repository.findByCategoryAndEntryValue(category.name(), value);
    if (rows.isEmpty()) {
      return false;
    }
    repository.deleteAll(rows);
    return true;
  }
}
