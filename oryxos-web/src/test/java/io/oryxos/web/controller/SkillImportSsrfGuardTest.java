package io.oryxos.web.controller;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 直接覆盖 {@link SkillApiController#guardPublicHost(URI)}：/import 虽只接受 GitHub tree URL，但 fetch
 * 跟随重定向时仍依赖本方法挡内网（含 IPv6 ULA）。
 */
class SkillImportSsrfGuardTest {

  @Test
  @DisplayName("IPv6 ULA fc00::/7 被拒绝（isSiteLocalAddress 不覆盖）")
  void ipv6UniqueLocalBlocked() {
    for (String url : new String[] {"http://[fd00::1]/SKILL.md", "http://[fc00::abcd]/x"}) {
      IllegalArgumentException ex =
          assertThrows(
              IllegalArgumentException.class,
              () -> SkillApiController.guardPublicHost(URI.create(url)));
      assertTrue(ex.getMessage().contains("拒绝访问"));
    }
  }

  @Test
  @DisplayName("回环 / 站点内网 / 链路本地 / CGNAT 仍被拒绝")
  void classicPrivateRangesStillBlocked() {
    for (String url :
        new String[] {
          "http://127.0.0.1/x",
          "http://10.0.0.5/x",
          "http://169.254.169.254/latest/meta-data/",
          "http://100.64.1.2/x"
        }) {
      assertThrows(
          IllegalArgumentException.class,
          () -> SkillApiController.guardPublicHost(URI.create(url)));
    }
  }

  @Test
  @DisplayName("公网字面量地址放行（解析层不抛）")
  void publicLiteralAllowed() {
    // 8.8.8.8 为公网 DNS；不发起真实 HTTP，只校验主机守卫
    assertDoesNotThrow(() -> SkillApiController.guardPublicHost(URI.create("https://8.8.8.8/x")));
  }

  @Test
  @DisplayName("IPv4-mapped / NAT64 / 6to4 / compatible 嵌入内网或元数据地址被拒绝")
  void embeddedPrivateBlocked() {
    for (String url :
        new String[] {
          "http://[::ffff:169.254.169.254]/latest/meta-data/",
          "http://[64:ff9b::a9fe:a9fe]/latest/meta-data/",
          "http://[64:ff9b::100.64.1.1]/x",
          "http://[2002:a9fe:a9fe::1]/latest/meta-data/",
          "http://[::a9fe:a9fe]/x",
          "http://[2001::5601:5601]/latest/meta-data/",
          "http://[2001:db8::5efe:a9fe:a9fe]/latest/meta-data/",
          "http://[2001:db8::200:5efe:a9fe:a9fe]/x"
        }) {
      assertThrows(
          IllegalArgumentException.class,
          () -> SkillApiController.guardPublicHost(URI.create(url)));
    }
  }

  @Test
  @DisplayName("NAT64 / 6to4 嵌入公网 IPv4 仍放行")
  void embeddedPublicIpv4Allowed() {
    assertDoesNotThrow(
        () -> SkillApiController.guardPublicHost(URI.create("http://[64:ff9b::8.8.8.8]/x")));
    assertDoesNotThrow(
        () -> SkillApiController.guardPublicHost(URI.create("http://[2002:808:808::1]/x")));
  }

  @Test
  @DisplayName("Teredo 嵌入公网 IPv4 仍放行")
  void teredoPublicIpv4Allowed() {
    assertDoesNotThrow(
        () -> SkillApiController.guardPublicHost(URI.create("http://[2001::f7f7:f7f7]/x")));
  }

  @Test
  @DisplayName("ISATAP 嵌入公网 IPv4 仍放行")
  void isatapPublicIpv4Allowed() {
    assertDoesNotThrow(
        () -> SkillApiController.guardPublicHost(URI.create("http://[2001:db8::5efe:808:808]/x")));
    assertDoesNotThrow(
        () ->
            SkillApiController.guardPublicHost(
                URI.create("http://[2001:db8::200:5efe:808:808]/x")));
  }
}
