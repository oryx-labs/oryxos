package io.oryxos.storage;

import org.springframework.data.jpa.repository.JpaRepository;

/** providers 读写：主键是 name（String）；CRUD 直接用 JpaRepository 内置方法。 */
public interface LlmProviderRepository extends JpaRepository<LlmProvider, String> {}
