package io.oryxos.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** memory_entries 手工建表 + LIMIT/LIKE 查询（16 节 SQLite 文件库模式）。 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class MemoryEntryRepositoryTest {

  @TempDir static Path dbDir;

  @DynamicPropertySource
  static void sqliteFile(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + dbDir.resolve("memory-test.db"));
    registry.add("spring.datasource.driver-class-name", () -> "org.sqlite.JDBC");
    registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
    registry.add(
        "spring.jpa.database-platform", () -> "org.hibernate.community.dialect.SQLiteDialect");
    registry.add("spring.sql.init.mode", () -> "always");
  }

  @Autowired private MemoryEntryRepository repository;

  private void insert(String scope, String content) {
    MemoryEntry e = new MemoryEntry();
    e.setScope(scope);
    e.setContent(content);
    repository.save(e);
  }

  @Test
  @DisplayName("手工建表脚本建出的 memory_entries 能存能读")
  void manualSchemaTableSupportsSaveAndRead() {
    insert("CORE", "用户叫小王");

    List<MemoryEntry> core = repository.findByScopeOrderByIdAsc("CORE");
    assertEquals(1, core.size());
    assertEquals("用户叫小王", core.get(0).getContent());
    assertTrue(core.get(0).getCreatedAt() != null, "@PrePersist 生效——表由手工脚本建出");
  }

  @Test
  @DisplayName("归档区 LIMIT 取最近 N")
  void archivalLimitReturnsMostRecent() {
    for (int i = 0; i < 5; i++) {
      insert("ARCHIVAL", "流水 " + i);
    }

    List<MemoryEntry> recent =
        repository.findByScopeOrderByIdDesc("ARCHIVAL", PageRequest.of(0, 3));

    assertEquals(3, recent.size());
    assertEquals("流水 4", recent.get(0).getContent()); // 最新在前
  }

  @Test
  @DisplayName("LIKE 检索只命中归档区")
  void searchArchivalMatchesOnlyArchival() {
    insert("CORE", "核心里也有 needle");
    insert("ARCHIVAL", "归档 needle 一条");
    insert("ARCHIVAL", "无关内容");

    List<MemoryEntry> hits = repository.searchArchival("%needle%");

    assertEquals(1, hits.size(), "核心区不参与检索");
    assertEquals("归档 needle 一条", hits.get(0).getContent());
  }
}
