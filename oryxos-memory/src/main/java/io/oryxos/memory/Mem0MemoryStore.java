package io.oryxos.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.oryxos.core.memory.MemoryScope;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/**
 * 档三：接一个自托管 Mem0 记忆层（数据不出域），把 append/load/recall 翻译成 Mem0 的 add/get/search—— 提炼、冲突消解、语义检索都交给 Mem0（第
 * 21 节第八节讲的能力）。
 *
 * <p>REST 端点按 Mem0 OSS server 约定编写；具体路径/参数以你部署的 mem0 版本为准。地址与凭证经装配从 环境变量注入（不落明文）。契约二在这一档变成"信任 Mem0
 * 的作用域机制"，契约四被升级为语义检索。
 */
public class Mem0MemoryStore implements LongTermMemoryStore {

  private static final String CORE_HEADER = "## 核心记忆";
  private static final String ARCHIVE_HEADER = "## 归档记忆";
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final RestClient restClient; // baseUrl 指向自托管 mem0 实例
  private final String userId; // 按当前 Agent/用户组织记忆作用域

  public Mem0MemoryStore(RestClient restClient, String userId) {
    this.restClient = restClient.mutate().build();
    this.userId = userId;
  }

  @Override
  public void append(String content, MemoryScope scope) {
    // Mem0 的 add：自己做提炼与冲突消解，scope 落进 metadata 供检索区分
    restClient
        .post()
        .uri("/v1/memories/")
        .contentType(MediaType.APPLICATION_JSON)
        .body(
            Map.of(
                "messages", List.of(Map.of("role", "user", "content", content)),
                "user_id", userId,
                "metadata", Map.of("scope", scope.name())))
        .retrieve()
        .toBodilessEntity();
  }

  @Override
  public String load() {
    return CORE_HEADER
        + "\n"
        + String.join("\n", getByScope("CORE"))
        + "\n"
        + ARCHIVE_HEADER
        + "\n"
        + String.join("\n", getByScope("ARCHIVAL"));
  }

  @Override
  public List<String> recallByKeyword(String keyword) {
    // Mem0 的 search 是语义检索——契约四的加强版实现
    String body =
        restClient
            .post()
            .uri("/v1/memories/search/")
            .contentType(MediaType.APPLICATION_JSON)
            .body(Map.of("query", keyword, "user_id", userId))
            .retrieve()
            .body(String.class);
    return extractMemoryTexts(body);
  }

  private List<String> getByScope(String scope) {
    String body =
        restClient
            .get()
            .uri("/v1/memories/?user_id={u}&scope={s}", userId, scope)
            .retrieve()
            .body(String.class);
    return extractMemoryTexts(body);
  }

  /** 从 Mem0 返回里抽取 memory 文本；结构随 mem0 版本略有差异，取常见的 results[].memory / memory 字段。 */
  private static List<String> extractMemoryTexts(String body) {
    List<String> texts = new ArrayList<>();
    if (body == null || body.isBlank()) {
      return texts;
    }
    try {
      JsonNode root = MAPPER.readTree(body);
      JsonNode results = root.has("results") ? root.get("results") : root;
      for (JsonNode item : results) {
        JsonNode memory = item.get("memory");
        if (memory != null) {
          texts.add(memory.asText());
        }
      }
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException("Mem0 响应解析失败", e);
    }
    return texts;
  }
}
