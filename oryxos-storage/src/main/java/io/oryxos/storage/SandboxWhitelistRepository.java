package io.oryxos.storage;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** sandbox_whitelist 表的 Spring Data 仓库。 */
public interface SandboxWhitelistRepository extends JpaRepository<SandboxWhitelistRow, Long> {

  boolean existsByCategoryAndEntryValue(String category, String entryValue);

  List<SandboxWhitelistRow> findByCategoryAndEntryValue(String category, String entryValue);
}
