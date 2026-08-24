package io.oryxos.tool.builtin;

import io.oryxos.core.memory.MemoryMdGuard;
import io.oryxos.tool.sandbox.ActionType;
import io.oryxos.tool.sandbox.Sandbox;
import io.oryxos.tool.sandbox.SandboxAction;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * 内置 HTTP 工具：http_get / http_post / http_request（任意方法）/ fetch_webpage（抓网页抽正文）/
 * download_file（下载到文件）。读请求走 {@code HTTP_READ}（默认放行 + SSRF）；写请求走 {@code HTTP_REQUEST}（域名白名单）。
 * 读写都禁自动重定向，由本类手动逐跳跟随并每跳重过沙箱校验。
 */
public class HttpTools {

  /** fetch_webpage 抽取正文的长度上限，防超大页面撑爆上下文。 */
  private static final int FETCH_TEXT_MAX = 20000;

  private static final String DEFAULT_METHOD = "GET";

  /** 手动跟随重定向的最大跳数（每跳都重新过沙箱校验，防公网 302→白名单外 / 内网）。 */
  private static final int MAX_REDIRECTS = 5;

  /** 连接 / 读取超时——外网慢站点不能拖死同步的 ReAct 触发。 */
  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

  private static final Duration READ_TIMEOUT = Duration.ofSeconds(20);

  private static final Pattern SCRIPT_STYLE =
      Pattern.compile("(?is)<(script|style)[^>]*>.*?</\\1>");
  private static final Pattern HTML_TAG = Pattern.compile("(?s)<[^>]+>");
  private static final Pattern INLINE_WS = Pattern.compile("[ \\t\\x0B\\f\\r]+");
  private static final Pattern BLANK_LINES = Pattern.compile("\\n{3,}");
  private static final Pattern HEADER_LINE_SEP = Pattern.compile("\\R");

  private static final String HTTP_SCHEME = "http";
  private static final String HTTPS_SCHEME = "https";
  private static final int DEFAULT_HTTP_PORT = 80;
  private static final int DEFAULT_HTTPS_PORT = 443;

  /** RFC 9110：这些 3xx 不得自动重放原方法与 body，浏览器会改成 GET。 */
  private static final int STATUS_MOVED_PERMANENTLY = 301;

  private static final int STATUS_FOUND = 302;
  private static final int STATUS_SEE_OTHER = 303;

  private final Sandbox sandbox;

  /** 读写共用：**禁自动重定向**，由本类手动逐跳跟随并每跳重过沙箱校验。不使用注入的 RestClient 默认跟随行为（否则写请求会在首跳过白名单后跟到任意 Location）。 */
  private final RestClient hopClient;

  public HttpTools(Sandbox sandbox, RestClient restClient) {
    this.sandbox = sandbox;
    Objects.requireNonNull(restClient, "restClient 不能为空"); // 保留构造签名，供 Spring 装配
    JdkClientHttpRequestFactory hopFactory =
        new JdkClientHttpRequestFactory(
            HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build());
    hopFactory.setReadTimeout(READ_TIMEOUT);
    this.hopClient = RestClient.builder().requestFactory(hopFactory).build();
  }

  /**
   * 读取一个 URL（GET），手动跟随重定向、**每跳都重过 {@code HTTP_READ} 沙箱校验**——首跳与每个 Location 都要过 SSRF 兜底，杜绝"公网入口 302
   * 跳内网"的绕过。返回最终响应体（{@code type} 为 String 或 byte[]）。
   */
  private <T> T read(String url, Class<T> type) {
    return read(url, null, type);
  }

  /**
   * 同 {@link #read(String, Class)}，并可带自定义请求头（供 {@code http_request} GET 使用）。跨源重定向剥离敏感头，对齐 {@link
   * #write}。
   */
  private <T> T read(String url, String headers, Class<T> type) {
    String current = url;
    String hopHeaders = headers;
    for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
      sandbox.enforce(new SandboxAction(ActionType.HTTP_READ, current)); // 每跳校验
      RestClient.RequestBodySpec spec = hopClient.method(HttpMethod.GET).uri(current);
      applyCustomHeaders(spec, hopHeaders);
      ResponseEntity<T> resp = spec.retrieve().toEntity(type);
      if (resp.getStatusCode().is3xxRedirection()) {
        String location = resp.getHeaders().getFirst("Location");
        if (location == null || location.isBlank()) {
          return resp.getBody(); // 3xx 但无 Location：返回现有响应体
        }
        String next = URI.create(current).resolve(location).toString();
        if (!sameOrigin(current, next)) {
          hopHeaders = stripSensitiveHeaders(hopHeaders);
        }
        current = next;
        continue;
      }
      return resp.getBody();
    }
    throw new IllegalStateException("重定向次数过多，拒绝: " + url);
  }

  /** 写请求（POST/PUT/…）：手动跟随重定向，**每跳都重过 {@code HTTP_REQUEST} 域名白名单**——杜绝"白名单内入口 302 跳白名单外"。 */
  private String write(
      HttpMethod method, String url, String headers, String body, boolean jsonBody) {
    String current = url;
    String hopHeaders = headers;
    HttpMethod hopMethod = method;
    String hopBody = body;
    boolean hopJsonBody = jsonBody;
    for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
      sandbox.enforce(new SandboxAction(ActionType.HTTP_REQUEST, current)); // 每跳校验
      RestClient.RequestBodySpec spec = hopClient.method(hopMethod).uri(current);
      applyCustomHeaders(spec, hopHeaders);
      if (hopBody != null && !hopBody.isBlank()) {
        if (hopJsonBody) {
          spec.contentType(MediaType.APPLICATION_JSON);
        }
        spec.body(hopBody);
      }
      ResponseEntity<String> resp = spec.retrieve().toEntity(String.class);
      if (resp.getStatusCode().is3xxRedirection()) {
        String location = resp.getHeaders().getFirst("Location");
        if (location == null || location.isBlank()) {
          return resp.getBody();
        }
        String next = URI.create(current).resolve(location).toString();
        // 跨源重定向剥离敏感头：对齐浏览器，防白名单内 A 302→白名单内 B 时泄露 Authorization/Cookie
        if (!sameOrigin(current, next)) {
          hopHeaders = stripSensitiveHeaders(hopHeaders);
        }
        // 301/302/303：不得把原 POST/PUT body 带到下一跳（RFC 9110 + 浏览器行为）。307/308 才保留方法与 body。
        if (switchesToGet(resp.getStatusCode().value())) {
          hopMethod = HttpMethod.GET;
          hopBody = null;
          hopJsonBody = false;
        }
        current = next;
        continue;
      }
      return resp.getBody();
    }
    throw new IllegalStateException("重定向次数过多，拒绝: " + url);
  }

  static boolean switchesToGet(int status) {
    return status == STATUS_MOVED_PERMANENTLY
        || status == STATUS_FOUND
        || status == STATUS_SEE_OTHER;
  }

  private static void applyCustomHeaders(RestClient.RequestBodySpec spec, String headers) {
    if (headers == null || headers.isBlank()) {
      return;
    }
    for (String line : HEADER_LINE_SEP.split(headers)) {
      int colon = line.indexOf(':');
      if (colon > 0) {
        spec.header(line.substring(0, colon).strip(), line.substring(colon + 1).strip());
      }
    }
  }

  /** scheme + host + effective port；跨源重定向时不得继续携带凭证类头。 */
  static boolean sameOrigin(String from, String to) {
    URI a = URI.create(from);
    URI b = URI.create(to);
    return Objects.equals(a.getScheme(), b.getScheme())
        && Objects.equals(hostOf(a), hostOf(b))
        && effectivePort(a) == effectivePort(b);
  }

  private static String hostOf(URI uri) {
    String host = uri.getHost();
    return host == null ? "" : host.toLowerCase(Locale.ROOT);
  }

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "IMPROPER_UNICODE",
      justification =
          "URI scheme tokens are ASCII; Locale.ROOT lowercasing is the correct case-fold for http/https comparison.")
  private static int effectivePort(URI uri) {
    int port = uri.getPort();
    if (port >= 0) {
      return port;
    }
    String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
    if (HTTPS_SCHEME.equals(scheme)) {
      return DEFAULT_HTTPS_PORT;
    }
    if (HTTP_SCHEME.equals(scheme)) {
      return DEFAULT_HTTP_PORT;
    }
    return -1;
  }

  /** 去掉跨源重定向不应转发的头（Authorization / Proxy-Authorization / Cookie / Cookie2）。 同浏览器对跨站重定向的凭证剥离习惯。 */
  static String stripSensitiveHeaders(String headers) {
    if (headers == null || headers.isBlank()) {
      return headers;
    }
    StringBuilder kept = new StringBuilder();
    for (String line : HEADER_LINE_SEP.split(headers)) {
      int colon = line.indexOf(':');
      if (colon <= 0) {
        continue;
      }
      String name = line.substring(0, colon).strip();
      if (isSensitiveHeaderName(name)) {
        continue;
      }
      if (kept.length() > 0) {
        kept.append('\n');
      }
      kept.append(name).append(':').append(line.substring(colon + 1).strip());
    }
    return kept.toString();
  }

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "IMPROPER_UNICODE",
      justification =
          "HTTP header names are ASCII tokens; Locale.ROOT lowercasing is the correct case-fold for Authorization/Cookie matching.")
  private static boolean isSensitiveHeaderName(String name) {
    String n = name.toLowerCase(Locale.ROOT);
    return "authorization".equals(n)
        || "proxy-authorization".equals(n)
        || "cookie".equals(n)
        || "cookie2".equals(n);
  }

  @Tool(name = "http_get", description = "发起一个 HTTP GET 请求，返回响应体")
  public String httpGet(@ToolParam(description = "要请求的完整 URL") String url) {
    return read(url, String.class); // 读：默认放行 + 内网黑名单 + 逐跳重定向重校验
  }

  @Tool(name = "http_post", description = "发起一个 HTTP POST 请求（JSON body），返回响应体")
  public String httpPost(
      @ToolParam(description = "要请求的完整 URL") String url,
      @ToolParam(description = "JSON 请求体") String body) {
    return write(HttpMethod.POST, url, null, body, true);
  }

  @Tool(
      name = "http_request",
      description = "发起任意方法的 HTTP 请求（GET/POST/PUT/PATCH/DELETE），可带请求头和请求体，返回响应体")
  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "IMPROPER_UNICODE",
      justification = "HTTP 方法名是 ASCII，Locale.ROOT 大写化是国际化安全的正确写法，仅用于把 method 归一成 GET/POST 等枚举名")
  public String httpRequest(
      @ToolParam(description = "HTTP 方法：GET / POST / PUT / PATCH / DELETE") String method,
      @ToolParam(description = "要请求的完整 URL") String url,
      @ToolParam(required = false, description = "可选请求头，每行一个「名: 值」") String headers,
      @ToolParam(required = false, description = "可选请求体（如 JSON 文本）") String body) {
    String verb = (method == null || method.isBlank()) ? DEFAULT_METHOD : method.strip();
    HttpMethod httpMethod = HttpMethod.valueOf(verb.toUpperCase(Locale.ROOT));
    // 按方法分级：GET 走读路径（放行 + 内网黑名单 + 逐跳重定向重校验），其余写方法走域名白名单 + 逐跳重定向重校验
    if (HttpMethod.GET.equals(httpMethod)) {
      return read(url, headers, String.class);
    }
    return write(httpMethod, url, headers, body, false);
  }

  @Tool(name = "fetch_webpage", description = "抓取一个网页并抽取可读正文（去掉 HTML 标签/脚本/样式），适合让模型阅读网页内容")
  public String fetchWebpage(@ToolParam(description = "网页 URL") String url) {
    String html = read(url, String.class); // 读：放行 + 内网黑名单 + 逐跳重定向重校验
    return htmlToText(html);
  }

  @Tool(name = "download_file", description = "下载一个 URL 的内容到指定本地文件路径（URL：默认放行 + SSRF；本地路径：文件白名单）")
  public String downloadFile(
      @ToolParam(description = "要下载的 URL") String url,
      @ToolParam(description = "保存到的本地文件路径") String path) {
    MemoryMdGuard.rejectMutation(path);
    sandbox.enforce(new SandboxAction(ActionType.FILE_WRITE, path)); // 先校验落盘路径
    byte[] data = read(url, byte[].class); // 读远端：放行 + 内网黑名单 + 逐跳重定向重校验
    byte[] bytes = data == null ? new byte[0] : data;
    try {
      Path file = Path.of(path);
      Path parent = file.getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
      // 写前复检：与 write_file 同款——createDirectories 之后、Files.write 之前。
      // 复检若放在建目录前，通过后父路径仍可被换成外向软链，写出白名单。
      sandbox.enforce(new SandboxAction(ActionType.FILE_WRITE, path));
      Files.write(file, bytes);
      return "已下载到: " + path + "（" + bytes.length + " 字节）";
    } catch (IOException e) {
      throw new UncheckedIOException("下载写入失败: " + path, e);
    }
  }

  /** 极简 HTML→正文：剥脚本/样式/标签、还原常见实体、压空白、截断。不追求完美渲染，只为可读。 */
  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "MODIFICATION_AFTER_VALIDATION",
      justification = "htmlToText 只是把网页 HTML 清洗成可读文本供模型阅读，非安全校验路径；实体还原与压空白的先后不涉及绕过任何校验")
  private static String htmlToText(String html) {
    if (html == null) {
      return "";
    }
    String text = SCRIPT_STYLE.matcher(html).replaceAll(" ");
    text = HTML_TAG.matcher(text).replaceAll(" ");
    text =
        text.replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'");
    text = INLINE_WS.matcher(text).replaceAll(" ");
    text = BLANK_LINES.matcher(text).replaceAll("\n\n").strip();
    return text.length() > FETCH_TEXT_MAX ? text.substring(0, FETCH_TEXT_MAX) + "\n…（已截断）" : text;
  }
}
