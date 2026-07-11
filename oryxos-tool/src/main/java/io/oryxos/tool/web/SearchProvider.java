package io.oryxos.tool.web;

import java.util.List;

/** 网页搜索抽象（接口先行，不绑定具体搜索引擎）：核心阶段一档实现，换 Google/Bing/Tavily 只新增实现类，不改工具与调用方。 */
public interface SearchProvider {

  /** 一条搜索结果：标题 + 链接 + 摘要。 */
  record SearchResult(String title, String url, String snippet) {}

  List<SearchResult> search(String query);
}
