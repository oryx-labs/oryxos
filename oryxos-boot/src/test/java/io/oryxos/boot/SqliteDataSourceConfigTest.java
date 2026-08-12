package io.oryxos.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** 只加载 DataSource 自动配置，验证内置 application.yml 的 SQLite 连接参数。 */
class SqliteDataSourceConfigTest {

  @TempDir Path tempDir;

  @Test
  void sqliteConnectionsEnableWalAndBusyTimeout() {
    new ApplicationContextRunner()
        .withInitializer(new ConfigDataApplicationContextInitializer())
        .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class))
        .withPropertyValues(
            "spring.datasource.url=jdbc:sqlite:" + tempDir.resolve("config-test.db"),
            "spring.datasource.driver-class-name=org.sqlite.JDBC")
        .run(
            context -> {
              assertTrue(context.isRunning());
              DataSource dataSource = context.getBean(DataSource.class);
              assertEquals("wal", pragmaText(dataSource, "journal_mode"));
              assertEquals("5000", pragmaText(dataSource, "busy_timeout"));
            });
  }

  private static String pragmaText(DataSource dataSource, String name) throws Exception {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement();
        ResultSet result = statement.executeQuery("PRAGMA " + name)) {
      assertTrue(result.next(), "PRAGMA " + name + " 应返回一行");
      return result.getString(1).toLowerCase();
    }
  }
}
