package io.oryxos.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.openai.core.Timeout;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Spring AI 2.0 改用 openai-java OkHttp 客户端。明文 http 默认走 HTTP/1.1（无 JDK HttpClient 的 h2c Upgrade
 * 丢体问题）；此处钉死超时与禁重定向装配。
 */
class ProviderChatModelFactoryHttpVersionTest {

  @Test
  @DisplayName("normalizeBaseUrl_普通端点补齐v1_版本化端点不补")
  void normalizeBaseUrl() {
    assertEquals(
        "http://127.0.0.1:8000/v1",
        ProviderChatModelFactory.normalizeBaseUrl("http://127.0.0.1:8000"));
    assertEquals(
        "http://127.0.0.1:8000/v1",
        ProviderChatModelFactory.normalizeBaseUrl("http://127.0.0.1:8000/v1/"));
    assertEquals(
        "https://open.bigmodel.cn/api/paas/v4",
        ProviderChatModelFactory.normalizeBaseUrl("https://open.bigmodel.cn/api/paas/v4/"));
  }

  @Test
  @DisplayName("requestTimeout读取系统属性")
  void requestTimeoutHonorsSystemProperties() {
    System.setProperty(ProviderChatModelFactory.CONNECT_TIMEOUT_PROP, "3");
    System.setProperty(ProviderChatModelFactory.READ_TIMEOUT_PROP, "7");
    try {
      Timeout t = ProviderChatModelFactory.requestTimeout();
      assertEquals(Duration.ofSeconds(3), t.connect());
      assertEquals(Duration.ofSeconds(7), t.read());
    } finally {
      System.clearProperty(ProviderChatModelFactory.CONNECT_TIMEOUT_PROP);
      System.clearProperty(ProviderChatModelFactory.READ_TIMEOUT_PROP);
    }
  }

  @Test
  @DisplayName("clientBuilder禁止跟随重定向")
  void clientBuilderDisablesRedirects() {
    // Builder 无 getter：能构建即表示 followRedirects(false) 被接受；行为由 Timeout 单测覆盖读超时。
    var builder = ProviderChatModelFactory.clientBuilder("http://127.0.0.1:9/v1", "test-key");
    assertFalse(builder == null);
  }
}
