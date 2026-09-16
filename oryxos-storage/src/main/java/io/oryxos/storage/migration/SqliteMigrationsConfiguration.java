package io.oryxos.storage.migration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * SQLite 存量收敛迁移 V2~V6 + V9 + V10 + V11 的装配（025 + Run 工作台 + 039 角色 + 040 OIDC + 041 资产治理事件）：仅
 * datasource url 为 SQLite 时注册。V6/V9/V10/V11 是 JavaMigration；PostgreSQL 目录另有成对 SQL。 V7/V8 为纯 SQL。
 */
@Configuration(proxyBeanMethods = false)
@Conditional(SqliteMigrationsConfiguration.OnSqliteDatasource.class)
public class SqliteMigrationsConfiguration {

  /** url 前缀判定（@ConditionalOnProperty 无前缀匹配能力）；缺省 url 即内置 SQLite 默认档。 */
  static class OnSqliteDatasource implements Condition {
    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
      String url =
          context.getEnvironment().getProperty("spring.datasource.url", "jdbc:sqlite:oryxos.db");
      return url.startsWith("jdbc:sqlite:");
    }
  }

  @Bean
  AuditColumnsMigration auditColumnsMigration() {
    return new AuditColumnsMigration();
  }

  @Bean
  MemoryAgentColumnMigration memoryAgentColumnMigration() {
    return new MemoryAgentColumnMigration();
  }

  @Bean
  ScheduleIdentityMigration scheduleIdentityMigration() {
    return new ScheduleIdentityMigration();
  }

  @Bean
  NotifyChannelConfigMigration notifyChannelConfigMigration() {
    return new NotifyChannelConfigMigration();
  }

  @Bean
  AgentRunColumnsMigration agentRunColumnsMigration() {
    return new AgentRunColumnsMigration();
  }

  @Bean
  WebUserRolesMigration webUserRolesMigration() {
    return new WebUserRolesMigration();
  }

  @Bean
  OidcIdentityMigration oidcIdentityMigration() {
    return new OidcIdentityMigration();
  }

  @Bean
  AssetGovernanceEventsMigration assetGovernanceEventsMigration() {
    return new AssetGovernanceEventsMigration();
  }
}
