package io.oryxos.storage;

import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** memory_entries 读写：核心区全量、归档区取最近 N（LIMIT）、归档区关键词 LIKE 检索。 */
public interface MemoryEntryRepository extends JpaRepository<MemoryEntry, Long> {

  /** 核心区：全量、按写入顺序（永不截断——契约二）。 */
  List<MemoryEntry> findByScopeOrderByIdAsc(String scope);

  /** 归档区：最近 N 条（id 降序 + Pageable 限量，即 LIMIT——截断只作用归档）。 */
  List<MemoryEntry> findByScopeOrderByIdDesc(String scope, Pageable pageable);

  /** 归档区关键词检索（LIKE，契约四）。 */
  @Query(
      "SELECT m FROM MemoryEntry m WHERE m.scope = 'ARCHIVAL' AND m.content LIKE :pattern"
          + " ORDER BY m.id ASC")
  List<MemoryEntry> searchArchival(@Param("pattern") String pattern);
}
