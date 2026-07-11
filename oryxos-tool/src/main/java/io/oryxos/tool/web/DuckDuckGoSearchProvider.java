package io.oryxos.tool.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.springframework.web.client.RestClient;

/**
 * 核心阶段唯一搜索实现：DuckDuckGo Instant Answer API（无需 API key，返回 JSON）。
 *
 * <p>端点由构造传入（默认公网），便于测试指向假服务；抓取 RelatedTopics 里的 Text/FirstURL 作为结果条目。搜不到即空列表——由上层工具决定怎么表达"没搜到"。
 */
public class DuckDuckGoSearchProvider implements SearchProvider {

  private static final String DEFAULT_ENDPOINT = "https://api.duckduckgo.com/";
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final RestClient restClient;
  private final String endpoint;

  public DuckDuckGoSearchProvider(RestClient restClient) {
    this(restClient, DEFAULT_ENDPOINT);
  }

  public DuckDuckGoSearchProvider(RestClient restClient, String endpoint) {
    this.restClient = restClient.mutate().build();
    this.endpoint = endpoint;
  }

  @Override
  public List<SearchResult> search(String query) {
    String body =
        restClient
            .get()
            .uri(endpoint + "?q={q}&format=json&no_html=1", query)
            .retrieve()
            .body(String.class);
    return parse(body);
  }

  private static List<SearchResult> parse(String body) {
    List<SearchResult> results = new ArrayList<>();
    if (body == null || body.isBlank()) {
      return results;
    }
    try {
      JsonNode root = MAPPER.readTree(body);
      JsonNode topics = root.path("RelatedTopics");
      for (JsonNode topic : topics) {
        JsonNode text = topic.get("Text");
        JsonNode url = topic.get("FirstURL");
        if (text != null && url != null) {
          String snippet = text.asText();
          String title = snippet.length() > 60 ? snippet.substring(0, 60) : snippet;
          results.add(new SearchResult(title, url.asText(), snippet));
        }
      }
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException("搜索结果解析失败", e);
    }
    return results;
  }
}
