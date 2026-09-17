package io.oryxos.web.security;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.util.pattern.PathPattern;

/**
 * 启动期端点覆盖（039 / #462）：受保护前缀上的 handler 必须被 {@link RequestActionResolver} 登记或显式跳过。
 *
 * <p>未登记返回 null 的路径在运行时已经 fail-closed；这里在启用 RBAC 时提前失败，避免新端点静默变成 403。 不改 URL pattern，也不调用 {@code
 * AuthorizationService}。
 */
public final class RbacEndpointCoverage {

  static final String SAMPLE_SEGMENT = "x";

  private static final String PREFIX_API_V1 = "/api/v1";

  private static final String PREFIX_API_V2 = "/api/v2";

  private static final String PREFIX_ACTUATOR = "/actuator";

  private static final String GAP_SEPARATOR = " ";

  private static final Pattern PATH_VAR = Pattern.compile("\\{[^}]+}");

  private static final Set<RequestMethod> METHODS_WHEN_UNSPECIFIED =
      Set.of(
          RequestMethod.GET,
          RequestMethod.POST,
          RequestMethod.PUT,
          RequestMethod.PATCH,
          RequestMethod.DELETE);

  private RbacEndpointCoverage() {}

  /** 未覆盖项。空列表表示登记完整。 {@code /admin/**} 不在受保护前缀内，不查。 */
  public static List<String> gaps(Iterable<MappedEndpoint> endpoints) {
    List<String> gaps = new ArrayList<>();
    if (endpoints == null) {
      return gaps;
    }
    for (MappedEndpoint endpoint : endpoints) {
      uncovered(endpoint.method(), endpoint.pattern()).ifPresent(gaps::add);
    }
    return List.copyOf(gaps);
  }

  static List<MappedEndpoint> fromHandlers(
      Iterable<Map.Entry<RequestMappingInfo, HandlerMethod>> handlers) {
    List<MappedEndpoint> endpoints = new ArrayList<>();
    if (handlers == null) {
      return endpoints;
    }
    for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : handlers) {
      addPatterns(endpoints, entry.getKey());
    }
    return endpoints;
  }

  static Optional<String> uncovered(String method, String pattern) {
    if (isBlank(method) || isBlank(pattern)) {
      return Optional.empty();
    }
    String path = concrete(pattern);
    if (!isGuarded(path)) {
      return Optional.empty();
    }
    if (RequestActionResolver.resolve(method, path) != null) {
      return Optional.empty();
    }
    return Optional.of(method.toUpperCase(Locale.ROOT) + GAP_SEPARATOR + pattern);
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }

  static String concrete(String pattern) {
    return PATH_VAR.matcher(pattern).replaceAll(SAMPLE_SEGMENT);
  }

  private static boolean isGuarded(String path) {
    return path.startsWith(PREFIX_API_V1)
        || path.startsWith(PREFIX_API_V2)
        || path.startsWith(PREFIX_ACTUATOR);
  }

  private static void addPatterns(List<MappedEndpoint> endpoints, RequestMappingInfo info) {
    if (info == null || info.getPathPatternsCondition() == null) {
      return;
    }
    Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
    Set<RequestMethod> toCheck = methods.isEmpty() ? METHODS_WHEN_UNSPECIFIED : methods;
    for (PathPattern pathPattern : info.getPathPatternsCondition().getPatterns()) {
      String pattern = pathPattern.getPatternString();
      for (RequestMethod method : toCheck) {
        endpoints.add(new MappedEndpoint(method.name(), pattern));
      }
    }
  }

  /** 一次 handler 声明。 */
  public record MappedEndpoint(String method, String pattern) {}
}
