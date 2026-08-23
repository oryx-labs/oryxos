package io.oryxos.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.oryxos.core.agent.ToolExecutionContext;
import io.oryxos.core.memory.MemoryEntryView;
import io.oryxos.core.memory.MemoryRecallCapability;
import io.oryxos.core.memory.MemoryScope;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * 档三（DELEGATED）：接自托管 mem0 OSS server（数据不出域），按 contracts §3 映射（015 FR-017）：
 *
 * <ul>
 *   <li>append(core)：{@code infer:false} 原文一字不差落库（契约二保真）+ metadata scope=CORE；
 *   <li>append(archival)：{@code infer:true} 交 mem0 提炼/冲突消解 + metadata scope=ARCHIVAL；
 *   <li>recallByKeyword：search 带 {@code filters:{scope:ARCHIVAL}}，返回侧再按 metadata 防御性过滤 ——mem0
 *       filters 有版本性 bug（issue #3773），双保险杜绝核心区串入检索（契约四范围）；
 *   <li>load：get_all 一次取回、客户端按 metadata scope 分区（core 全量 + archival 窗口）。
 * </ul>
 *
 * <p>端点形态：mem0 OSS server 无 {@code /v1} 前缀（POST /memories、POST /search、GET /memories）。 per-Agent
 * 隔离（契约不变量 4）：有 Agent 上下文时 user_id = 基础 user-id + ":" + agent；无上下文 沿用基础 user-id（存量数据升级后仍可达）。鉴权：配置
 * api-key 时同时带 {@code Authorization: Bearer} 与 {@code X-API-Key}（OSS 两种模式都认）；本地免鉴权部署留空即可。
 * 故障（连接/HTTP 异常）统一转可读 {@link IllegalStateException}——工具层如实呈现入审计、对话不中断。
 */
public class Mem0MemoryStore implements LongTermMemoryStore {

  private static final String CORE_HEADER = "## 核心记忆";
  private static final String ARCHIVE_HEADER = "## 归档记忆";
  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** mem0 返回体的分页容器字段（部分版本包一层 items）。 */
  private static final String ITEMS_FIELD = "items";

  /** 归档区注入窗口（与本地档 MAX_ARCHIVE_ROWS 对齐——契约二：截断只作用归档）。 */
  private static final int MAX_ARCHIVE_ROWS = 100;

  private final RestClient restClient; // baseUrl 指向自托管 mem0 实例
  private final String userId; // 基础作用域；per-Agent 时追加 ":" + agent

  public Mem0MemoryStore(RestClient restClient, String userId) {
    this(restClient, userId, "");
  }

  public Mem0MemoryStore(RestClient restClient, String userId, String apiKey) {
    RestClient.Builder builder = restClient.mutate();
    if (apiKey != null && !apiKey.isBlank()) {
      builder.defaultHeader("Authorization", "Bearer " + apiKey);
      builder.defaultHeader("X-API-Key", apiKey);
    }
    this.restClient = builder.build();
    this.userId = userId;
  }

  private String scopedUserId() {
    String agent = ToolExecutionContext.agentName();
    return agent == null || agent.isBlank() ? userId : userId + ":" + agent;
  }

  @Override
  public MemoryEntryView append(String content, MemoryScope scope) {
    // core 必须原文保真（infer:false）；archival 交 mem0 提炼与冲突消解（infer:true）
    Map<String, Object> body = new HashMap<>(8);
    body.put("messages", List.of(Map.of("role", "user", "content", content)));
    body.put("user_id", scopedUserId());
    body.put("metadata", Map.of("scope", scope.name()));
    body.put("infer", scope == MemoryScope.ARCHIVAL);
    execute(
        "写入记忆",
        () ->
            restClient
                .post()
                .uri("/memories")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toBodilessEntity());
    return new MemoryEntryView(content, java.time.Instant.now());
  }

  @Override
  public String load() {
    List<Mem0Item> items = getAll();
    List<String> core = new ArrayList<>();
    List<String> archive = new ArrayList<>();
    for (Mem0Item item : items) {
      if (MemoryScope.CORE.name().equals(item.scope())) {
        core.add(item.text());
      } else {
        archive.add(item.text());
      }
    }
    List<String> recentArchive =
        archive.size() <= MAX_ARCHIVE_ROWS
            ? archive
            : archive.subList(archive.size() - MAX_ARCHIVE_ROWS, archive.size());
    return CORE_HEADER
        + "\n"
        + String.join("\n", core)
        + "\n"
        + ARCHIVE_HEADER
        + "\n"
        + String.join("\n", recentArchive);
  }

  @Override
  public List<String> recallByKeyword(String keyword) {
    // mem0 的 search 是语义检索——契约四的加强版；filters + 返回侧防御双保险（#3773）
    String body =
        execute(
            "检索记忆",
            () ->
                restClient
                    .post()
                    .uri("/search")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(
                        Map.of(
                            "query", keyword,
                            "user_id", scopedUserId(),
                            "filters", Map.of("scope", MemoryScope.ARCHIVAL.name())))
                    .retrieve()
                    .body(String.class));
    return parseItems(body).stream()
        .filter(item -> !MemoryScope.CORE.name().equals(item.scope())) // 核心区绝不进检索结果
        .map(Mem0Item::text)
        .toList();
  }

  @Override
  public MemoryRecallCapability capabilities() {
    return MemoryRecallCapability.DELEGATED;
  }

  @Override
  public List<MemoryEntryView> archivalEntries() {
    return List.of(); // DELEGATED 档：时间路与索引对账不适用（引擎不会调它）
  }

  private List<Mem0Item> getAll() {
    String body =
        execute(
            "读取记忆",
            () ->
                restClient
                    .get()
                    .uri("/memories?user_id={u}", scopedUserId())
                    .retrieve()
                    .body(String.class));
    return parseItems(body);
  }

  /** 统一故障口径：连接/HTTP 异常 → 可读 IllegalStateException（工具层转可读结果入审计）。 */
  private static <T> T execute(String action, Supplier<T> call) {
    try {
      return call.get();
    } catch (RestClientException e) {
      throw new IllegalStateException(
          "mem0 " + action + "失败：" + e.getMessage() + "（检查 memory.mem0.base-url 与服务状态）", e);
    }
  }

  private record Mem0Item(String text, String scope) {}

  /** 容错解析：results[] / items[] / 顶层数组皆可；文本取 memory（回退 text），scope 取 metadata。 */
  private static List<Mem0Item> parseItems(String body) {
    List<Mem0Item> items = new ArrayList<>();
    if (body == null || body.isBlank()) {
      return items;
    }
    try {
      JsonNode root = MAPPER.readTree(body);
      JsonNode results = root.has("results") ? root.get("results") : root;
      if (results.has(ITEMS_FIELD)) {
        results = results.get(ITEMS_FIELD);
      }
      for (JsonNode item : results) {
        JsonNode memory = item.has("memory") ? item.get("memory") : item.get("text");
        if (memory == null) {
          continue;
        }
        JsonNode metadata = item.get("metadata");
        String scope =
            metadata != null && metadata.hasNonNull("scope") ? metadata.get("scope").asText() : "";
        items.add(new Mem0Item(memory.asText(), scope));
      }
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException("mem0 响应解析失败：" + e.getMessage(), e);
    }
    return items;
  }
}
