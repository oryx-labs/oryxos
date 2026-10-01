package io.oryxos.web.security;

import io.oryxos.core.policy.Action;
import io.oryxos.core.policy.ResourceRef;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Locale;
import org.springframework.http.HttpMethod;

/**
 * 路径 → 动作映射（039 第二刀）：契约 §4.2 的唯一登记处。
 *
 * <p>返回语义：
 *
 * <ul>
 *   <li>{@link Resolution#skip()} 工厂 / {@link Resolution#isSkip()} —— 不裁决（认证面 / 存活面 / 渠道入站 / Alipay
 *       兼容口）
 *   <li>{@link Resolution#of(Action, ResourceRef)} —— 命中登记表
 *   <li>{@code null} —— 未登记：调用方 fail-closed 拒绝
 * </ul>
 *
 * <p>相对契约初稿的补登记（路由盘点）：{@code /api/v2/schedules/**} 与 v1 调度同档；{@code POST /api/v1} （Alipay
 * 截断网关）按入站渠道同口径只认证不裁决——否则 RBAC 一开即打挂支付宝回调。
 */
public final class RequestActionResolver {

  private static final String PATH_SEP = "/";

  private static final String PATH_API_V1 = "/api/v1";

  private static final String PATH_API_V1_SLASH = "/api/v1/";

  private static final String PATH_API_V1_HEALTH = "/api/v1/health";

  private static final String PATH_API_V1_AUTH = "/api/v1/auth";

  private static final String PATH_API_V1_INFO = "/api/v1/info";

  private static final String PATH_ACTUATOR = "/actuator";

  private static final String PATH_ACTUATOR_HEALTH = "/actuator/health";

  private static final String PATH_API_V1_A2A = "/api/v1/a2a";

  private static final String PATH_CHANNELS_INBOUND = "/api/v1/channels/inbound";

  private static final String PATH_SESSIONS = "/api/v1/sessions";

  private static final String PATH_RUNS = "/api/v1/runs";

  private static final String PATH_AGENTS = "/api/v1/agents";

  private static final String PATH_AGENTS_PREFIX = "/api/v1/agents/";

  private static final String PATH_KNOWLEDGE = "/api/v1/knowledge";

  private static final String PATH_KNOWLEDGE_PREFIX = "/api/v1/knowledge/";

  private static final String PATH_SKILLS = "/api/v1/skills";

  private static final String PATH_SKILLS_PREFIX = "/api/v1/skills/";

  private static final String PATH_PERSONAS = "/api/v1/personas";

  private static final String PATH_SCHEDULES = "/api/v1/schedules";

  private static final String PATH_V2_SCHEDULES = "/api/v2/schedules";

  private static final String PATH_V2_AGENTS_PREFIX = "/api/v2/agents/";

  private static final String PATH_CHANNELS = "/api/v1/channels";

  private static final String PATH_NOTIFY_CHANNELS = "/api/v1/notify-channels";

  private static final String PATH_MCP_SERVERS = "/api/v1/mcp-servers";

  private static final String PATH_TOOL_POLICY = "/api/v1/tool-policy";

  private static final String PATH_SANDBOX = "/api/v1/sandbox";

  private static final String PATH_AUDIT = "/api/v1/audit";

  private static final String PATH_WORKSPACE = "/api/v1/workspace";

  private static final String PATH_PROVIDERS = "/api/v1/providers";

  private static final String PATH_PRICING = "/api/v1/pricing";

  private static final String PATH_PROFILES = "/api/v1/profiles";

  private static final String PATH_TOOLS = "/api/v1/tools";

  private static final String PATH_INSTANCES = "/api/v1/instances";

  private static final String PATH_TEAMS = "/api/v1/teams";

  private static final String PATH_ORGS = "/api/v1/orgs";

  private static final String PATH_IDENTITY_MAPPINGS = "/api/v1/identity-mappings";

  private static final String PATH_APPROVALS = "/api/v1/approvals";

  private static final String PATH_APPROVAL_CALLBACKS = "/api/v1/approvals/callbacks";

  private static final String PATH_USERS = "/api/v1/users";

  private static final String SEGMENT_TEAMS = "/teams";

  private static final String SUFFIX_INVOKE = "/invoke";

  private static final String SEGMENT_SCHEDULES = "/schedules/";

  private static final String SUFFIX_RUN = "/run";

  private static final String QUERY_MARK = "?";

  private RequestActionResolver() {}

  /**
   * @return 映射结果；{@code null} 表示未登记（应拒绝）
   */
  public static Resolution resolve(HttpServletRequest request) {
    if (request == null) {
      return null;
    }
    String method = request.getMethod() == null ? "" : request.getMethod().toUpperCase(Locale.ROOT);
    String path = normalize(request.getRequestURI());
    return resolve(method, path);
  }

  /** 纯函数入口（单测友好）。 */
  public static Resolution resolve(String method, String path) {
    String m = method == null ? "" : method.toUpperCase(Locale.ROOT);
    String p = normalize(path);
    if (HttpMethod.OPTIONS.matches(m)) {
      return Resolution.skip();
    }
    if (isSkipPath(p)) {
      return Resolution.skip();
    }
    // 支付宝截断网关：与 channels/inbound 同属外部平台回调
    if (isAlipayCompatGateway(m, p)) {
      return Resolution.skip();
    }
    if (isUnder(p, PATH_CHANNELS_INBOUND) || isUnder(p, PATH_APPROVAL_CALLBACKS)) {
      return Resolution.skip();
    }
    if (isUnder(p, PATH_ACTUATOR)) {
      return Resolution.of(Action.READ_WORKSPACE, ResourceRef.workspace());
    }
    if (matchesInvoke(p) && HttpMethod.POST.matches(m)) {
      return Resolution.of(
          Action.RUN_AGENT, ResourceRef.agent(segmentAfter(p, PATH_AGENTS_PREFIX)));
    }
    // A2A JSON-RPC: a peer drives an Agent turn through message/send, the same capability as
    // POST /api/v1/agents/{name}/invoke. The Agent is named in the request body, so the resource
    // is unqualified — as it is for personas and schedules.
    if (isUnder(p, PATH_API_V1_A2A)) {
      return Resolution.of(Action.RUN_AGENT, ResourceRef.agent(null));
    }
    if (isUnder(p, PATH_SESSIONS) || isUnder(p, PATH_RUNS)) {
      return Resolution.of(Action.MANAGE_SESSIONS, ResourceRef.session(null));
    }
    if (isUnder(p, PATH_AGENTS)) {
      return readOrManage(
          m, Action.MANAGE_AGENTS, ResourceRef.agent(segmentAfter(p, PATH_AGENTS_PREFIX)));
    }
    if (isUnder(p, PATH_KNOWLEDGE)) {
      return readOrManage(
          m,
          Action.MANAGE_KNOWLEDGE,
          ResourceRef.knowledge(segmentAfter(p, PATH_KNOWLEDGE_PREFIX)));
    }
    if (isUnder(p, PATH_SKILLS)) {
      return readOrManage(
          m, Action.MANAGE_SKILLS, ResourceRef.skill(segmentAfter(p, PATH_SKILLS_PREFIX)));
    }
    if (isUnder(p, PATH_PERSONAS) || isUnder(p, PATH_SCHEDULES)) {
      return readOrManage(m, Action.MANAGE_AGENTS, ResourceRef.agent(null));
    }
    // v2 调度：与 v1 schedules 同档（盘点缺口补登记）
    if (isUnder(p, PATH_V2_SCHEDULES) || matchesV2AgentScheduleRun(p)) {
      return readOrManage(m, Action.MANAGE_AGENTS, ResourceRef.agent(null));
    }
    if (isChannelManagePath(p)) {
      return Resolution.of(Action.MANAGE_CHANNELS, ResourceRef.channel(null));
    }
    if (isUnder(p, PATH_TOOL_POLICY) || isUnder(p, PATH_SANDBOX) || isUnder(p, PATH_APPROVALS)) {
      return Resolution.of(Action.MANAGE_POLICIES, ResourceRef.policy());
    }
    if (isUnder(p, PATH_AUDIT)) {
      return Resolution.of(Action.READ_AUDIT, ResourceRef.audit());
    }
    if (isUnder(p, PATH_WORKSPACE)) {
      return readOrManage(m, Action.MANAGE_WORKSPACE, ResourceRef.workspace());
    }
    if (isUnder(p, PATH_PROVIDERS) || isUnder(p, PATH_PRICING)) {
      return readOrManage(m, Action.MANAGE_WORKSPACE, ResourceRef.workspace());
    }
    if (isReadWorkspaceExactOrUnder(p)) {
      if (isRead(m)) {
        return Resolution.of(Action.READ_WORKSPACE, ResourceRef.workspace());
      }
      return null;
    }
    // teams / orgs / user team memberships / identity-mappings：ADMIN-only（MANAGE_MEMBERS）；含 GET
    if (isUnder(p, PATH_TEAMS)
        || isUnder(p, PATH_ORGS)
        || isUnder(p, PATH_IDENTITY_MAPPINGS)
        || isUserTeamsPath(p)) {
      return Resolution.of(Action.MANAGE_MEMBERS, ResourceRef.member(null));
    }
    // 未登记：fail-closed
    return null;
  }

  /** /api/v1/users/{username}/teams[/{teamId}] */
  private static boolean isUserTeamsPath(String path) {
    String prefix = PATH_USERS + PATH_SEP;
    if (!path.startsWith(prefix)) {
      return false;
    }
    String rest = path.substring(prefix.length());
    int slash = rest.indexOf(PATH_SEP);
    if (slash < 0) {
      return false;
    }
    String afterUser = rest.substring(slash);
    return SEGMENT_TEAMS.equals(afterUser) || afterUser.startsWith(SEGMENT_TEAMS + PATH_SEP);
  }

  private static boolean isSkipPath(String path) {
    boolean healthOrAuth =
        isExact(path, PATH_API_V1_HEALTH)
            || isUnder(path, PATH_API_V1_AUTH)
            || isExact(path, PATH_ACTUATOR_HEALTH)
            || isUnder(path, PATH_ACTUATOR_HEALTH);
    return healthOrAuth;
  }

  private static boolean isAlipayCompatGateway(String method, String path) {
    boolean alipayRoot = isExact(path, PATH_API_V1) || isExact(path, PATH_API_V1_SLASH);
    return alipayRoot && HttpMethod.POST.matches(method);
  }

  private static boolean isChannelManagePath(String path) {
    return isUnder(path, PATH_CHANNELS)
        || isUnder(path, PATH_NOTIFY_CHANNELS)
        || isUnder(path, PATH_MCP_SERVERS);
  }

  private static boolean isReadWorkspaceExactOrUnder(String path) {
    return isExact(path, PATH_API_V1_INFO)
        || isUnder(path, PATH_PROFILES)
        || isUnder(path, PATH_TOOLS)
        || isUnder(path, PATH_INSTANCES);
  }

  private static Resolution readOrManage(String method, Action manage, ResourceRef resource) {
    if (isRead(method)) {
      return Resolution.of(Action.READ_WORKSPACE, ResourceRef.workspace());
    }
    return Resolution.of(manage, resource);
  }

  private static boolean isRead(String method) {
    return HttpMethod.GET.matches(method) || HttpMethod.HEAD.matches(method);
  }

  private static boolean matchesInvoke(String path) {
    // /api/v1/agents/{name}/invoke (prefix without trailing slash for isUnder)
    if (!isUnder(path, PATH_AGENTS)) {
      return false;
    }
    return path.endsWith(SUFFIX_INVOKE);
  }

  private static boolean matchesV2AgentScheduleRun(String path) {
    // /api/v2/agents/{profile}/schedules/{key}/run
    boolean v2Agent = path.startsWith(PATH_V2_AGENTS_PREFIX);
    boolean hasSchedule = path.contains(SEGMENT_SCHEDULES);
    boolean runSuffix = path.endsWith(SUFFIX_RUN);
    return v2Agent && hasSchedule && runSuffix;
  }

  private static String segmentAfter(String path, String prefix) {
    if (!path.startsWith(prefix) || path.length() <= prefix.length()) {
      return null;
    }
    String rest = path.substring(prefix.length());
    int slash = rest.indexOf(PATH_SEP);
    String seg = slash < 0 ? rest : rest.substring(0, slash);
    return seg.isBlank() ? null : seg;
  }

  private static String normalize(String path) {
    if (path == null || path.isBlank()) {
      return "";
    }
    // 去掉查询串（若调用方误传）
    int q = path.indexOf(QUERY_MARK);
    String p = q < 0 ? path : path.substring(0, q);
    if (p.length() > 1 && p.endsWith(PATH_SEP)) {
      // 保留 "/api/v1/" 这种兼容根；其它尾斜杠去掉以便前缀匹配
      if (!isExact(p, PATH_API_V1_SLASH) && !isExact(p, PATH_API_V1)) {
        return p.substring(0, p.length() - 1);
      }
    }
    return p;
  }

  private static boolean isExact(String path, String expected) {
    return expected.equals(path);
  }

  private static boolean isUnder(String path, String prefix) {
    return path.equals(prefix) || path.startsWith(prefix + PATH_SEP);
  }

  /** 一次映射结果。 */
  public static final class Resolution {
    private final boolean skipped;
    private final Action action;
    private final ResourceRef resource;

    private Resolution(boolean skipped, Action action, ResourceRef resource) {
      this.skipped = skipped;
      this.action = action;
      this.resource = resource;
    }

    public static Resolution skip() {
      return new Resolution(true, null, null);
    }

    public static Resolution of(Action action, ResourceRef resource) {
      return new Resolution(false, action, resource);
    }

    /**
     * @return {@code true} if this resolution skips authorization.
     */
    public boolean isSkip() {
      return skipped;
    }

    public Action action() {
      return action;
    }

    public ResourceRef resource() {
      return resource;
    }
  }
}
