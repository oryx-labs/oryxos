package io.oryxos.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.oryxos.core.auth.Principal;
import io.oryxos.core.auth.Role;
import io.oryxos.core.policy.Action;
import io.oryxos.core.policy.AuthorizationService;
import io.oryxos.core.policy.RoleBasedAuthorizationServiceImpl;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 012-web-auth 验收 harness：WebUserServiceTest——账号管理口径（哈希非明文、校验对错、禁用失效、 重名/弱密码/用户名非法）在此钉死。镜像
 * SessionManagerTest 的 @DataJpaTest + @DynamicPropertySource 模式。
 */
@org.springframework.transaction.annotation.Transactional
abstract class WebUserServiceContractTest {

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

  @Test
  @DisplayName("create默认VIEWER_setRoles往返_未知token降权")
  void roles_roundTripAndUnknownTokenIgnored() {
    WebUserService svc = service();
    WebUser created = svc.create("carol", "password1");
    assertEquals("VIEWER", created.getRoles());
    assertEquals(java.util.Set.of(io.oryxos.core.auth.Role.VIEWER), svc.rolesOf("carol"));

    svc.setRoles("carol", java.util.Set.of(io.oryxos.core.auth.Role.EDITOR));
    assertEquals("EDITOR", svc.list().get(0).getRoles());
    assertEquals(java.util.Set.of(io.oryxos.core.auth.Role.EDITOR), svc.rolesOf("carol"));

    // 直接写脏数据：未知 token 忽略，合法 token 保留
    WebUser dirty = repository.findByUsername("carol").orElseThrow();
    dirty.setRoles("EDITOR,NOPE,ADMIN");
    repository.save(dirty);
    assertEquals(
        java.util.Set.of(io.oryxos.core.auth.Role.EDITOR, io.oryxos.core.auth.Role.ADMIN),
        svc.rolesOf("carol"));

    assertTrue(svc.hasAdminAccount());
    assertEquals(java.util.Set.of(), svc.rolesOf("nobody"));
  }

  @Test
  @DisplayName("rolesOf_已禁用账号返回空集（039 FR-008：账号不存在/禁用返回空集）")
  void rolesOf_disabledAccountYieldsNoRoles() {
    WebUserService svc = service();
    svc.create("carol", "password1");
    svc.setRoles("carol", Set.of(Role.ADMIN));
    assertEquals(Set.of(Role.ADMIN), svc.rolesOf("carol"));

    svc.disable("carol");

    assertEquals(Set.of(), svc.rolesOf("carol"));
    assertFalse(svc.isEnabledUser("carol"));
    assertFalse(svc.hasAdminAccount());
  }

  @Test
  @DisplayName("decide_已禁用管理员的治理动作全部拒绝（039 FR-008 端到端：rolesOf → decide）")
  void decide_disabledAdminDeniedGovernanceActions() {
    WebUserService svc = service();
    svc.create("carol", "password1");
    svc.setRoles("carol", Set.of(Role.ADMIN));

    // 生产装配口径：default-user-roles 默认收紧为空 ⇒ 主体无角色即拒绝。
    AuthorizationService authz = new RoleBasedAuthorizationServiceImpl(Set.of(), Set.of());

    Principal active = Principal.user("carol", "carol", svc.rolesOf("carol"));
    assertTrue(authz.decide(active, Action.MANAGE_POLICIES, null).allowed());

    svc.disable("carol");
    Principal disabled = Principal.user("carol", "carol", svc.rolesOf("carol"));

    assertFalse(authz.decide(disabled, Action.MANAGE_POLICIES, null).allowed());
    assertFalse(authz.decide(disabled, Action.MANAGE_MEMBERS, null).allowed());
    assertFalse(authz.decide(disabled, Action.MANAGE_CHANNELS, null).allowed());
  }

  @Test
  @DisplayName("rolesOf_启用账号不受影响_禁用只停权不清角色（反向：未过度修正）")
  void rolesOf_enabledAccountKeepsRolesAndDisableIsReversible() {
    WebUserService svc = service();
    svc.create("carol", "password1");
    svc.setRoles("carol", Set.of(Role.EDITOR, Role.ADMIN));

    assertEquals(Set.of(Role.EDITOR, Role.ADMIN), svc.rolesOf("carol"));

    svc.disable("carol");
    assertEquals(Set.of(), svc.rolesOf("carol"));
    assertEquals(1, svc.list().size()); // 禁用不删账号，仍可在管理面看到并再启用

    svc.enable("carol");

    assertEquals(Set.of(Role.EDITOR, Role.ADMIN), svc.rolesOf("carol")); // roles 列未被清空
    assertTrue(svc.verify("carol", "password1"));
    assertTrue(svc.hasAdminAccount());
  }

  /** 用户名归一必须作用在**同一个规范形式**上：写入 strip 了，判重与查找也必须 strip， 否则「同一个字符串建得出、却删不掉/登不上」。 */
  @Test
  @DisplayName("create_带首尾空格的同名_报已存在而不是把唯一约束异常抛给调用方")
  void create_paddedDuplicateReportsAlreadyExists() {
    WebUserService svc = service();
    svc.create("  bob  ", "password1");

    // javadoc 承诺重名抛 IllegalArgumentException；不归一的话 existsByUsername 查的是 "  bob  "，
    // 查不到 → 落库时才撞唯一约束 → JpaSystemException（CLI 打出裸 SQL 错误、REST 变 500）
    assertThrows(IllegalArgumentException.class, () -> svc.create("  bob  ", "password1"));
    assertThrows(IllegalArgumentException.class, () -> svc.create("bob", "password1"));
  }

  @Test
  @DisplayName("verify_带首尾空格的用户名_照样能登录")
  void verify_acceptsPaddedUsername() {
    WebUserService svc = service();
    svc.create("  bob  ", "password1");

    assertTrue(svc.verify("  bob  ", "password1"), "建得出来的字符串必须也登得上");
    assertTrue(svc.verify("bob", "password1"));
  }

  @Test
  @DisplayName("按名字操作的方法_带首尾空格一律认（删/禁用/启用/改密/授权）")
  void nameBasedOperationsAcceptTheSameStringThatCreatedTheUser() {
    WebUserService svc = service();
    svc.create("  bob  ", "password1");

    svc.setRoles("  bob  ", Set.of(Role.EDITOR));
    assertEquals(Set.of(Role.EDITOR), svc.rolesOf("bob"));

    svc.changePassword("  bob  ", "password2");
    assertTrue(svc.verify("bob", "password2"));

    svc.disable("  bob  ");
    assertFalse(svc.isEnabledUser("bob"));
    svc.enable("  bob  ");
    assertTrue(svc.isEnabledUser("bob"));

    svc.delete("  bob  ");
    assertThrows(IllegalArgumentException.class, () -> svc.disable("bob"), "删掉后必须真的不在");
  }
}
