package io.oryxos.core.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 生成 Run Event 展示载荷：限制大小、遮蔽常见敏感字段。先脱敏再限长。 */
@edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
    value = "CRLF_INJECTION_LOGS",
    justification = "日志中的异常消息已通过 sanitize() 去掉 CR/LF。")
public final class AgentRunEventPayloads {

  public static final int SCHEMA_VERSION = 1;
  static final int MAX_FIELD_CHARS = 400;
  static final int MAX_PAYLOAD_CHARS = 2000;

  private static final Logger LOG = LoggerFactory.getLogger(AgentRunEventPayloads.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final Set<String> SENSITIVE =
      Set.of(
          "authorization",
          "cookie",
          "token",
          "accesstoken",
          "apikey",
          "api_key",
          "password",
          "secret",
          "passwd",
          "bearer");
  private static final Pattern SECRET_TEXT =
      Pattern.compile(
          "(?i)(authorization\\s*[:=]\\s*(?:bearer\\s+)?\\S+)|(bearer\\s+[a-z0-9._\\-+/=]+)|(cookie\\s*[:=]\\s*[^\\r\\n]*)|((?:api[_-]?key|password|passwd|secret|token)\\s*[:=]\\s*\\S+)");

  private AgentRunEventPayloads() {}

  public static String json(Map<String, Object> fields) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("schemaVersion", SCHEMA_VERSION);
    boolean truncated = false;
    int used = 0;
    if (fields != null) {
      for (Map.Entry<String, Object> entry : fields.entrySet()) {
        Object value = redact(entry.getKey(), entry.getValue());
        String text = value == null ? "" : String.valueOf(value);
        if (text.length() > MAX_FIELD_CHARS) {
          value = text.substring(0, MAX_FIELD_CHARS) + "…";
          truncated = true;
        }
        String asText = value == null ? "" : String.valueOf(value);
        if (used + asText.length() > MAX_PAYLOAD_CHARS) {
          truncated = true;
          continue;
        }
        used += asText.length();
        body.put(entry.getKey(), value);
      }
    }
    if (truncated) {
      body.put("truncated", true);
    }
    try {
      return MAPPER.writeValueAsString(body);
    } catch (JsonProcessingException e) {
      LOG.warn("Run event payload 序列化失败：{}", sanitize(e.getMessage()));
      return "{\"schemaVersion\":1,\"truncated\":true}";
    }
  }

  public static String summarizeJson(String raw) {
    if (raw == null || raw.isBlank()) {
      return "";
    }
    try {
      JsonNode node = MAPPER.readTree(raw);
      redactNode(node);
      String compact = MAPPER.writeValueAsString(node);
      return limit(compact);
    } catch (JsonProcessingException e) {
      return summarizeText(raw);
    }
  }

  public static String summarizeText(String raw) {
    if (raw == null) {
      return "";
    }
    return limit(SECRET_TEXT.matcher(raw).replaceAll("***"));
  }

  private static String limit(String text) {
    if (text.length() > MAX_FIELD_CHARS) {
      return text.substring(0, MAX_FIELD_CHARS) + "…";
    }
    return text;
  }

  private static Object redact(String key, Object value) {
    if (value == null) {
      return null;
    }
    if (isSensitive(key)) {
      return "***";
    }
    if (value instanceof Map<?, ?> map) {
      Map<String, Object> copy = new LinkedHashMap<>();
      for (Map.Entry<?, ?> entry : map.entrySet()) {
        String childKey = String.valueOf(entry.getKey());
        copy.put(childKey, redact(childKey, entry.getValue()));
      }
      return copy;
    }
    if (value instanceof List<?> list) {
      return list.stream().map(item -> redact(key, item)).toList();
    }
    if (value instanceof String text) {
      return SECRET_TEXT.matcher(text).replaceAll("***");
    }
    return value;
  }

  private static void redactNode(JsonNode node) {
    if (node == null) {
      return;
    }
    if (node.isObject()) {
      redactObject((ObjectNode) node);
    } else if (node.isArray()) {
      ArrayNode array = (ArrayNode) node;
      for (int i = 0; i < array.size(); i++) {
        JsonNode child = array.get(i);
        if (child != null && child.isTextual()) {
          array.set(i, array.textNode(SECRET_TEXT.matcher(child.asText()).replaceAll("***")));
        } else {
          redactNode(child);
        }
      }
    }
  }

  private static void redactObject(ObjectNode object) {
    Iterator<String> names = object.fieldNames();
    java.util.List<String> keys = new java.util.ArrayList<>();
    names.forEachRemaining(keys::add);
    for (String key : keys) {
      JsonNode child = object.get(key);
      if (isSensitive(key)) {
        object.put(key, "***");
      } else if (child != null && child.isTextual()) {
        object.put(key, SECRET_TEXT.matcher(child.asText()).replaceAll("***"));
      } else {
        redactNode(child);
      }
    }
  }

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "IMPROPER_UNICODE",
      justification = "Field-name matching is ASCII-only; Locale.ROOT fold is intentional.")
  private static boolean isSensitive(String key) {
    if (key == null) {
      return false;
    }
    String normalized = key.toLowerCase(Locale.ROOT).replace("-", "").replace("_", "");
    return SENSITIVE.contains(key.toLowerCase(Locale.ROOT))
        || SENSITIVE.contains(normalized)
        || normalized.contains("password")
        || normalized.contains("secret")
        || normalized.contains("apikey")
        || normalized.contains("accesstoken")
        || normalized.contains("authorization")
        || normalized.contains("bearer")
        || "cookie".equals(normalized)
        || normalized.contains("token");
  }

  private static String sanitize(String value) {
    return value == null ? "" : value.replace('\r', '_').replace('\n', '_');
  }
}
