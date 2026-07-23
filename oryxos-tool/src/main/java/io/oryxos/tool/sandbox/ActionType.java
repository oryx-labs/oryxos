package io.oryxos.tool.sandbox;

/**
 * 涉外动作类型（24 节起）：文件读 / 文件写 / Shell 命令 / HTTP 读 / HTTP 写。
 *
 * <p>HTTP 分读写（第 32 节）：{@code HTTP_READ}（GET/抓网页/下载/搜索）默认放行——只挡内网/回环/云元数据等 SSRF 目标，让研究型 Agent
 * 自由取数；{@code HTTP_REQUEST}（POST/PUT/… 写请求）仍走域名白名单——防止把数据外发到任意端点。file/shell 一律白名单不放宽。
 */
public enum ActionType {
  FILE_READ,
  FILE_WRITE,
  SHELL_COMMAND,
  /** HTTP 读（GET 类）：默认放行 + 内网/SSRF 黑名单兜底。 */
  HTTP_READ,
  /** HTTP 写（POST/PUT/PATCH/DELETE）：域名白名单 + 内网/SSRF 黑名单。 */
  HTTP_REQUEST
}
