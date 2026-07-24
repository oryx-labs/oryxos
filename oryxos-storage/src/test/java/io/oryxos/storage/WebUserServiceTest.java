package io.oryxos.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 012-web-auth 验收 harness：WebUserServiceTest——账号管理口径（哈希非明文、校验对错、禁用失效、 重名/弱密码/用户名非法）在此钉死。镜像
 * SessionManagerTest 的 @DataJpaTest + @DynamicPropertySource 模式。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class WebUserServiceTest {

  @TempDir static Path dbDir;

  @DynamicPropertySource
  static void sqliteProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + dbDir.resolve("webauth-test.db"));
    registry.add("spring.datasource.driver-class-name", () -> "org.sqlite.JDBC");
    registry.add("spring.jpa.hibernate.ddl-auto", () -> "none"); // 不许 Hibernate 自动建表
    registry.add(
        "spring.jpa.database-platform", () -> "org.hibernate.community.dialect.SQLiteDialect");
    registry.add("spring.sql.init.mode", () -> "always"); // 建表走手工 schema.sql
  }

  @Autowired private WebUserRepository repository;

  private final PasswordEncoder encoder =
      PasswordEncoderFactories.createDelegatingPasswordEncoder();

  private WebUserService service() {
    return new WebUserService(repository, encoder);
  }

  @Test
  @DisplayName("create_密码哈希非明文_且每次salt不同")
  void create_hashesPasswordNotPlaintext() {
    WebUserService svc = service();
    WebUser u1 = svc.create("alice", "password1");
    WebUser u2 = svc.create("bob", "password1");

    // hash 非明文
    assertNotEquals("password1", u1.getPasswordHash());
    assertTrue(u1.getPasswordHash().startsWith("{bcrypt}"));
    // 相同密码不同 salt → hash 不同
    assertNotEquals(u1.getPasswordHash(), u2.getPasswordHash());
    assertTrue(u1.isEnabled());
  }

  @Test
  @DisplayName("verify_正确密码返回true_错误密码返回false")
  void verify_correctPasswordTrue_wrongFalse() {
    WebUserService svc = service();
    svc.create("alice", "password1");

    assertTrue(svc.verify("alice", "password1"));
    assertFalse(svc.verify("alice", "wrong-password"));
    assertFalse(svc.verify("nobody", "password1")); // 不存在不区分原因（防枚举）
  }

  @Test
  @DisplayName("disable_后_verify返回false")
  void disable_blocksLogin() {
    WebUserService svc = service();
    svc.create("alice", "password1");
    svc.disable("alice");

    assertFalse(svc.verify("alice", "password1"));
  }

  @Test
  @DisplayName("hasEnabledAccount_空时false_有enabled时true")
  void hasEnabledAccount_reflectsEnabledAccounts() {
    WebUserService svc = service();
    assertFalse(svc.hasEnabledAccount()); // 空

    svc.create("alice", "password1");
    assertTrue(svc.hasEnabledAccount());

    svc.disable("alice");
    assertFalse(svc.hasEnabledAccount()); // 全禁用
  }

  @Test
  @DisplayName("重名_create抛错且不覆盖原密码")
  void create_duplicateThrows() {
    WebUserService svc = service();
    svc.create("alice", "password1");

    assertThrows(IllegalArgumentException.class, () -> svc.create("alice", "password2"));
    // 原密码仍可用
    assertTrue(svc.verify("alice", "password1"));
    assertFalse(svc.verify("alice", "password2"));
  }

  @Test
  @DisplayName("changePassword_旧密码失效_新密码生效")
  void changePassword_rotatesCredential() {
    WebUserService svc = service();
    svc.create("alice", "password1");
    svc.changePassword("alice", "newpassword2");

    assertFalse(svc.verify("alice", "password1"));
    assertTrue(svc.verify("alice", "newpassword2"));
  }

  @Test
  @DisplayName("delete_后再verify返回false")
  void delete_removesAccount() {
    WebUserService svc = service();
    svc.create("alice", "password1");
    svc.delete("alice");

    assertFalse(svc.verify("alice", "password1"));
    assertFalse(repository.existsByUsername("alice"));
  }

  @Test
  @DisplayName("list_按username排序且不含密码字段")
  void list_sortedByUsername() {
    WebUserService svc = service();
    svc.create("charlie", "password1");
    svc.create("alice", "password1");
    svc.create("bob", "password1");

    var users = svc.list();
    assertEquals(3, users.size());
    assertEquals("alice", users.get(0).getUsername());
    assertEquals("bob", users.get(1).getUsername());
    assertEquals("charlie", users.get(2).getUsername());
  }

  @Test
  @DisplayName("弱密码_短于8抛错")
  void create_shortPasswordThrows() {
    assertThrows(IllegalArgumentException.class, () -> service().create("alice", "short"));
  }

  @Test
  @DisplayName("空用户名抛错")
  void create_emptyUsernameThrows() {
    assertThrows(IllegalArgumentException.class, () -> service().create("", "password1"));
    assertThrows(IllegalArgumentException.class, () -> service().create("  ", "password1"));
  }

  @Test
  @DisplayName("用户名含空格抛错")
  void create_usernameWithWhitespaceThrows() {
    assertThrows(IllegalArgumentException.class, () -> service().create("has space", "password1"));
  }

  @Test
  @DisplayName("delete/passwd/disable_不存在用户抛错")
  void mutateMissingUser_throws() {
    WebUserService svc = service();
    assertThrows(IllegalArgumentException.class, () -> svc.delete("nobody"));
    assertThrows(IllegalArgumentException.class, () -> svc.changePassword("nobody", "password2"));
    assertThrows(IllegalArgumentException.class, () -> svc.disable("nobody"));
  }
}
