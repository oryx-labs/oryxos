package io.oryxos.core.agent;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AgentRunEventPayloadsTest {

  @Test
  void redactNestedObjectSecrets() {
    String json =
        AgentRunEventPayloads.summarizeJson(
            "{\"headers\":{\"Authorization\":\"Bearer abc.def\",\"apiKey\":\"sk-live\"},\"url\":\"https://example\"}");
    assertFalse(json.contains("abc.def"));
    assertFalse(json.contains("sk-live"));
    assertTrue(json.contains("***"));
    assertTrue(json.contains("https://example"));
  }

  @Test
  void redactArraySecrets() {
    String json =
        AgentRunEventPayloads.summarizeJson(
            "[{\"password\":\"hunter2\"},{\"note\":\"cookie=session-xyz\"}]");
    assertFalse(json.contains("hunter2"));
    assertFalse(json.contains("session-xyz"));
    assertTrue(json.contains("***"));
  }

  @Test
  void redactFreeTextSecretsThenLimit() {
    String text =
        AgentRunEventPayloads.summarizeText(
            "failed Authorization: Bearer super-secret-token and password=hunter2");
    assertFalse(text.contains("super-secret-token"));
    assertFalse(text.contains("hunter2"));
    assertTrue(text.contains("***"));
  }

  @Test
  void redactEntireMultiValueCookieHeader() {
    String text =
        AgentRunEventPayloads.summarizeText(
            "request failed; Cookie: session=abc; access_token=secret; preference=dark\nnext line");

    assertFalse(text.contains("session=abc"));
    assertFalse(text.contains("access_token=secret"));
    assertFalse(text.contains("preference=dark"));
    assertTrue(text.contains("next line"));
  }

  @Test
  void jsonMapRedactsBeforeTruncation() {
    String json =
        AgentRunEventPayloads.json(
            Map.of(
                "apiKey",
                "sk-live-should-hide",
                "items",
                List.of(Map.of("token", "rotating-token", "name", "http_get"))));
    assertFalse(json.contains("sk-live-should-hide"));
    assertFalse(json.contains("rotating-token"));
    assertTrue(json.contains("http_get"));
  }
}
