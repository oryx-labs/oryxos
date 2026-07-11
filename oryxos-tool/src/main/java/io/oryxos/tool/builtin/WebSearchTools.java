package io.oryxos.tool.builtin;

import io.oryxos.tool.sandbox.ActionType;
import io.oryxos.tool.sandbox.Sandbox;
import io.oryxos.tool.sandbox.SandboxAction;
import io.oryxos.tool.web.SearchProvider;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * 内置网页搜索工具：web_search——日报/调研类 Agent 的第一入口。
 *
 * <p>搜索走网络，同 http_get 一样先过域名白名单（HTTP_REQUEST 检查位）；具体引擎由 {@link SearchProvider}
 * 决定，本工具只负责把结果渲染成模型好读的文本。
 */
public class WebSearchTools {

  private static final int MAX_RESULTS = 8;

  private final Sandbox sandbox;
  private final SearchProvider provider;

  public WebSearchTools(Sandbox sandbox, SearchProvider provider) {
    this.sandbox = sandbox;
    this.provider = provider;
  }

  @Tool(name = "web_search", description = "用搜索引擎检索网页，返回标题、链接和摘要列表")
  public String webSearch(@ToolParam(description = "搜索关键词") String query) {
    // 搜索是一次涉外请求，与 http_get 共享域名白名单机制（HTTP_REQUEST）
    sandbox.enforce(new SandboxAction(ActionType.HTTP_REQUEST, "web_search:" + query));
    List<SearchProvider.SearchResult> results = provider.search(query);
    if (results.isEmpty()) {
      return "（未搜到相关结果）";
    }
    return results.stream()
        .limit(MAX_RESULTS)
        .map(r -> "- " + r.title() + "\n  " + r.url() + "\n  " + r.snippet())
        .collect(Collectors.joining("\n"));
  }
}
