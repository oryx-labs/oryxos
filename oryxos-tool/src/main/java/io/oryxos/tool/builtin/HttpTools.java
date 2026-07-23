package io.oryxos.tool.builtin;

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
 * download_file（下载到文件）。域名白名单检查位第一行——不过校验请求根本不发出。
 */
public class HttpTools {

  /** fetch_webpage 抽取正文的长度上限，防超大页面撑爆上下文。 */
  private static final int FETCH_TEXT_MAX = 20000;

  private static final String DEFAULT_METHOD = "GET";

  /** 读请求手动跟随重定向的最大跳数（每跳都重新过 SSRF 校验，防公网 302→内网）。 */
  private static final int MAX_REDIRECTS = 5;

  /** 读请求连接 / 读取超时——外网慢站点不能拖死同步的 ReAct 触发（否则浏览器 Failed to fetch）。 */
  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

  private static final Duration READ_TIMEOUT = Duration.ofSeconds(20);

  private static final Pattern SCRIPT_STYLE =
      Pattern.compile("(?is)<(script|style)[^>]*>.*?</\\1>");
  private static final Pattern HTML_TAG = Pattern.compile("(?s)<[^>]+>");
  private static final Pattern INLINE_WS = Pattern.compile("[ \\t\\x0B\\f\\r]+");
  private static final Pattern BLANK_LINES = Pattern.compile("\\n{3,}");
  private static final Pattern HEADER_LINE_SEP = Pattern.compile("\\R");

  private final Sandbox sandbox;
  private final RestClient restClient;
  // 读专用客户端：**禁自动重定向**，由本类手动逐跳跟随并每跳重过 SSRF 校验（防公网 302 → 内网绕过）。
  private final RestClient readClient;

  public HttpTools(Sandbox sandbox, RestClient restClient) {
    this.sandbox = sandbox;
    this.restClient = restClient.mutate().build();
    JdkClientHttpRequestFactory readFactory =
        new JdkClientHttpRequestFactory(
            HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build());
    readFactory.setReadTimeout(READ_TIMEOUT);
    this.readClient = RestClient.builder().requestFactory(readFactory).build();
  }

  /**
   * 读取一个 URL（GET），手动跟随重定向、**每跳都重过 {@code HTTP_READ} 沙箱校验**——首跳与每个 Location 都要过 SSRF 兜底，杜绝"公网入口 302
   * 跳内网"的绕过。返回最终响应体（{@code type} 为 String 或 byte[]）。
   */
  private <T> T read(String url, Class<T> type) {
    String current = url;
    for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
      sandbox.enforce(new SandboxAction(ActionType.HTTP_READ, current)); // 每跳校验
      ResponseEntity<T> resp = readClient.get().uri(current).retrieve().toEntity(type);
      if (resp.getStatusCode().is3xxRedirection()) {
        String location = resp.getHeaders().getFirst("Location");
        if (location == null || location.isBlank()) {
          return resp.getBody(); // 3xx 但无 Location：返回现有响应体
        }
        current = URI.create(current).resolve(location).toString();
        continue;
      }
      return resp.getBody();
    }
    throw new IllegalStateException("重定向次数过多，拒绝: " + url);
  }

  @Tool(name = "http_get", description = "发起一个 HTTP GET 请求，返回响应体")
  public String httpGet(@ToolParam(description = "要请求的完整 URL") String url) {
    return read(url, String.class); // 读：默认放行 + 内网黑名单 + 逐跳重定向重校验
  }

  @Tool(name = "http_post", description = "发起一个 HTTP POST 请求（JSON body），返回响应体")
  public String httpPost(
      @ToolParam(description = "要请求的完整 URL") String url,
      @ToolParam(description = "JSON 请求体") String body) {
    sandbox.enforce(new SandboxAction(ActionType.HTTP_REQUEST, url));
    return restClient
        .post()
        .uri(url)
        .contentType(MediaType.APPLICATION_JSON)
        .body(body)
        .retrieve()
        .body(String.class);
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
    // 按方法分级：GET 走读路径（放行 + 内网黑名单 + 逐跳重定向重校验），其余写方法走域名白名单
    if (HttpMethod.GET.equals(httpMethod)) {
      return read(url, String.class);
    }
    sandbox.enforce(new SandboxAction(ActionType.HTTP_REQUEST, url));
    RestClient.RequestBodySpec spec = restClient.method(httpMethod).uri(url);
    if (headers != null && !headers.isBlank()) {
      for (String line : HEADER_LINE_SEP.split(headers)) {
        int colon = line.indexOf(':');
        if (colon > 0) {
          spec.header(line.substring(0, colon).strip(), line.substring(colon + 1).strip());
        }
      }
    }
    if (body != null && !body.isBlank()) {
      spec.body(body);
    }
    return spec.retrieve().body(String.class);
  }

  @Tool(name = "fetch_webpage", description = "抓取一个网页并抽取可读正文（去掉 HTML 标签/脚本/样式），适合让模型阅读网页内容")
  public String fetchWebpage(@ToolParam(description = "网页 URL") String url) {
    String html = read(url, String.class); // 读：放行 + 内网黑名单 + 逐跳重定向重校验
    return htmlToText(html);
  }

  @Tool(name = "download_file", description = "下载一个 URL 的内容到指定本地文件路径（域名 + 路径都过白名单）")
  public String downloadFile(
      @ToolParam(description = "要下载的 URL") String url,
      @ToolParam(description = "保存到的本地文件路径") String path) {
    sandbox.enforce(new SandboxAction(ActionType.FILE_WRITE, path)); // 先校验落盘路径
    byte[] data = read(url, byte[].class); // 读远端：放行 + 内网黑名单 + 逐跳重定向重校验
    byte[] bytes = data == null ? new byte[0] : data;
    try {
      Path file = Path.of(path);
      Path parent = file.getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
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
