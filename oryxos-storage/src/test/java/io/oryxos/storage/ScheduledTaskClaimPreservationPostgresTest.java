package io.oryxos.storage;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** PostgreSQL 档：与 SQLite 共用到点认领不回退的行为契约。 */
@PostgresJpaTest
class ScheduledTaskClaimPreservationPostgresTest
    extends ScheduledTaskClaimPreservationContractTest {

  @DynamicPropertySource
  static void datasource(DynamicPropertyRegistry registry) {
    registry.add(
        "spring.datasource.url",
        () -> PgTestSupport.databaseUrl(ScheduledTaskClaimPreservationPostgresTest.class));
  }
}
