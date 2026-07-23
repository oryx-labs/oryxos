package io.oryxos.web.skill;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * "从 GitHub 拉取"（第 32 节）：把一个 GitHub 目录 URL（如 {@code
 * https://github.com/obra/superpowers/tree/main/skills/brainstorming}）整个递归拉下来， 落成一份"相对路径 → 内容"的
 * Map，交给 {@code SkillService#importFiles} 原样落盘——不是抓网页正文，是拉整个文件夹（SKILL.md + 脚本/参考资料等），跟"一个目录 = 一个
 * Skill"的既有形态对齐。
 *
 * <p>只认 GitHub，不做通用 git 托管兼容：用 GitHub Contents API（{@code
 * /repos/{owner}/{repo}/contents/{path}?ref={branch}}）列目录、递归子目录，逐个文件按其 {@code download_url} 取原始内容。
 * HTTP 收发通过构造注入的 {@code httpGet} 函数完成（测试可替身，生产由 {@link io.oryxos.web.controller.SkillApiController}
 * 的 SSRF 加固版 fetch 提供）。
 */
public class GithubFolderFetcher {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final Pattern TREE_URL =
      Pattern.compile("^https://github\\.com/([^/]+)/([^/]+)/tree/([^/]+)/(.+?)/?$");

  private static final int MAX_FILES = 100;
  private static final int MAX_TOTAL_BYTES = 5 * 1024 * 1024;
  private static final String PATH_SEPARATOR = "/";

  private final Function<URI, String> httpGet;

  public GithubFolderFetcher(Function<URI, String> httpGet) {
    this.httpGet = httpGet;
  }

  /** 一个已解析的 GitHub 目录目标：owner/repo/branch/该目录相对仓库根的路径。 */
  public record Target(String owner, String repo, String branch, String path) {

    /** 目录末段作为 Skill 名的推断值（如 {@code skills/brainstorming} → {@code brainstorming}）。 */
    public String fallbackName() {
      String[] segs = path.split(PATH_SEPARATOR);
      return segs.length == 0 ? repo : segs[segs.length - 1];
    }
  }

  /** 解析 {@code github.com/<owner>/<repo>/tree/<branch>/<path>} 形式的目录 URL；不匹配则拒绝。 */
  public static Target parseTreeUrl(String url) {
    if (url == null) {
      throw new IllegalArgumentException("url 为空");
    }
    Matcher m = TREE_URL.matcher(url.strip());
    if (!m.matches()) {
      throw new IllegalArgumentException(
          "仅支持 GitHub 目录 URL，形如 https://github.com/<owner>/<repo>/tree/<branch>/<path>: " + url);
    }
    return new Target(m.group(1), m.group(2), m.group(3), m.group(4));
  }

  /** 递归拉取该目录下所有文件，键是相对该目录的路径（如 {@code SKILL.md}、{@code scripts/foo.py}）。 */
  public Map<String, String> fetchFolder(Target target) {
    Map<String, String> files = new LinkedHashMap<>();
    long[] totalBytes = {0};
    fetchDir(target, target.path(), files, totalBytes);
    if (files.isEmpty()) {
      throw new IllegalArgumentException("该 GitHub 目录为空或不存在: " + target.path());
    }
    return files;
  }

  private void fetchDir(Target target, String dirPath, Map<String, String> out, long[] totalBytes) {
    String api =
        "https://api.github.com/repos/%s/%s/contents/%s?ref=%s"
            .formatted(target.owner(), target.repo(), encodePath(dirPath), target.branch());
    JsonNode entries = parseJson(httpGet.apply(URI.create(api)));
    if (!entries.isArray()) {
      throw new IllegalArgumentException("GitHub 返回的不是一个目录: " + dirPath);
    }
    List<JsonNode> sorted = new ArrayList<>();
    entries.forEach(sorted::add);
    for (JsonNode entry : sorted) {
      String type = entry.path("type").asText("");
      String path = entry.path("path").asText("");
      String relative =
          path.startsWith(target.path() + PATH_SEPARATOR)
              ? path.substring(target.path().length() + 1)
              : path;
      if ("dir".equals(type)) {
        fetchDir(target, path, out, totalBytes);
        continue;
      }
      if (!"file".equals(type)) {
        continue; // 跳过 symlink/submodule 之类
      }
      if (out.size() >= MAX_FILES) {
        throw new IllegalArgumentException("目录文件数超过上限（" + MAX_FILES + "），拒绝导入");
      }
      String downloadUrl = entry.path("download_url").asText(null);
      if (downloadUrl == null) {
        continue;
      }
      String content = httpGet.apply(URI.create(downloadUrl));
      totalBytes[0] += content.length();
      if (totalBytes[0] > MAX_TOTAL_BYTES) {
        throw new IllegalArgumentException("目录总大小超过上限（5MB），拒绝导入");
      }
      out.put(relative, content);
    }
  }

  private static String encodePath(String path) {
    // Contents API 的 path 段允许 '/'，逐段做 URL 编码后再拼回去
    StringBuilder sb = new StringBuilder();
    for (String seg : path.split(PATH_SEPARATOR)) {
      if (!sb.isEmpty()) {
        sb.append('/');
      }
      sb.append(java.net.URLEncoder.encode(seg, java.nio.charset.StandardCharsets.UTF_8));
    }
    return sb.toString();
  }

  private static JsonNode parseJson(String body) {
    try {
      return MAPPER.readTree(body);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalArgumentException("GitHub 返回内容不是合法 JSON", e);
    }
  }
}
