package io.oryxos.core.mcp;

import java.util.List;

/**
 * 内置的业界公开 MCP server 目录（静态数据，不依赖网络）。条目均取自 {@code config/mcp_servers.yaml.example} 里已核实的社区/官方
 * server（npx/uvx 可直接起的 stdio server，或有据可查的官方远程 endpoint），不臆造未经证实的地址。
 *
 * <p>{@code http} 传输的一个已知限制：核心阶段的 SSE 客户端传输不支持自定义请求头（见 {@code McpClientService} 注释），因此需要
 * Authorization 头鉴权的远程 server 目前连不上鉴权网关——目录里仍收录它们，标注这个限制，等 SDK 传输层升级后再补全。
 */
public final class McpCatalog {

  private McpCatalog() {}

  public static final List<McpCatalogEntry> ENTRIES =
      List.of(
          new McpCatalogEntry(
              "github",
              "GitHub",
              "Issue / PR / 代码搜索 / 仓库文件。官方远程 MCP（HTTP）——当前核心阶段暂不支持转发 Authorization"
                  + " 头，鉴权网关会拒绝，可先用下面的 stdio 版替代。",
              McpServerConfig.TRANSPORT_HTTP,
              null,
              "https://api.githubcopilot.com/mcp/",
              List.of("GITHUB_TOKEN"),
              "https://github.com/github/github-mcp-server"),
          new McpCatalogEntry(
              "github-stdio",
              "GitHub（stdio 版）",
              "Issue / PR / 代码搜索 / 仓库文件，本地子进程运行，token 走环境变量。",
              McpServerConfig.TRANSPORT_STDIO,
              "npx -y @modelcontextprotocol/server-github",
              null,
              List.of("GITHUB_PERSONAL_ACCESS_TOKEN"),
              "https://github.com/modelcontextprotocol/servers-archived/tree/main/src/github"),
          new McpCatalogEntry(
              "gitlab",
              "GitLab",
              "项目 / Issue / Merge Request（gitlab.com 或自建实例）。",
              McpServerConfig.TRANSPORT_STDIO,
              "npx -y @modelcontextprotocol/server-gitlab",
              null,
              List.of("GITLAB_PERSONAL_ACCESS_TOKEN"),
              "https://github.com/modelcontextprotocol/servers-archived/tree/main/src/gitlab"),
          new McpCatalogEntry(
              "slack",
              "Slack",
              "发消息、读频道。需要一个 Slack Bot 并安装到工作区。",
              McpServerConfig.TRANSPORT_STDIO,
              "npx -y @modelcontextprotocol/server-slack",
              null,
              List.of("SLACK_BOT_TOKEN", "SLACK_TEAM_ID"),
              "https://github.com/modelcontextprotocol/servers-archived/tree/main/src/slack"),
          new McpCatalogEntry(
              "notion",
              "Notion",
              "读写 Notion 页面与数据库。需创建 internal integration 并把目标页面分享给它。",
              McpServerConfig.TRANSPORT_STDIO,
              "npx -y @notionhq/notion-mcp-server",
              null,
              List.of("NOTION_TOKEN"),
              "https://github.com/makenotion/notion-mcp-server"),
          new McpCatalogEntry(
              "brave-search",
              "Brave Search",
              "网页搜索。需要 Brave Search API key（有免费额度）。",
              McpServerConfig.TRANSPORT_STDIO,
              "npx -y @modelcontextprotocol/server-brave-search",
              null,
              List.of("BRAVE_API_KEY"),
              "https://brave.com/search/api"),
          new McpCatalogEntry(
              "playwright",
              "Playwright 浏览器自动化",
              "驱动真实浏览器：打开页面、点击、填表、截图、抓取内容。无需凭证。",
              McpServerConfig.TRANSPORT_STDIO,
              "npx -y @playwright/mcp@latest",
              null,
              List.of(),
              "https://github.com/microsoft/playwright-mcp"),
          new McpCatalogEntry(
              "filesystem",
              "Filesystem（增强文件操作）",
              "在内置 read_file/write_file/list_dir 之外，提供移动、搜索、树状浏览、编辑等更丰富的文件操作。",
              McpServerConfig.TRANSPORT_STDIO,
              "npx -y @modelcontextprotocol/server-filesystem /srv/agent-workspace",
              null,
              List.of(),
              "https://github.com/modelcontextprotocol/servers/tree/main/src/filesystem"),
          new McpCatalogEntry(
              "sqlite",
              "SQLite",
              "查询本地 .db 文件（数据库路径按需替换模板里的占位路径）。",
              McpServerConfig.TRANSPORT_STDIO,
              "uvx mcp-server-sqlite --db-path /absolute/path/to/database.db",
              null,
              List.of(),
              "https://github.com/modelcontextprotocol/servers-archived/tree/main/src/sqlite"));

  public static List<McpCatalogEntry> all() {
    return ENTRIES;
  }
}
