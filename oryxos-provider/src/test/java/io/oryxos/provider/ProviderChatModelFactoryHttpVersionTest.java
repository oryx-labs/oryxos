package io.oryxos.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.http.HttpClient;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 本地推理端点回归：JDK 21 HttpClient 默认 HTTP/2，对明文 http:// 的 vLLM/Ollama（uvicorn）发 h2c 升级会 丢请求体（'input':
 * None → 400）。工厂构造的 HttpClient 必须强制 HTTP/1.1。
 */
class ProviderChatModelFactoryHttpVersionTest {

  @Test
  @DisplayName("工厂构造的HttpClient强制HTTP1_1_避免对本地vLLM_Ollama的h2c升级丢请求体")
  void httpClientForcesHttp11() {
    HttpClient client = ProviderChatModelFactory.httpClient(Duration.ofSeconds(10));
    assertEquals(HttpClient.Version.HTTP_1_1, client.version());
  }

  @Test
  @DisplayName("工厂构造的HttpClient禁止自动跟随重定向（防恶意baseUrl SSRF）")
  void httpClientDisablesAutoRedirects() {
    HttpClient client = ProviderChatModelFactory.httpClient(Duration.ofSeconds(10));
    assertEquals(HttpClient.Redirect.NEVER, client.followRedirects());
  }
}
