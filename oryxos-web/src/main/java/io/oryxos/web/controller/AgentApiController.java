package io.oryxos.web.controller;

import io.oryxos.core.OryxTool;
import io.oryxos.core.agent.AgencyAgentsImporter;
import io.oryxos.core.agent.AgencyAgentsParser;
import io.oryxos.core.agent.AgencyAgentsParser.ParsedExpert;
import io.oryxos.core.agent.AgentExecutionService;
import io.oryxos.core.agent.AgentLifecycleService;
import io.oryxos.core.agent.AgentService;
import io.oryxos.core.agent.AgentValidation;
import io.oryxos.core.agent.TraceContext;
import io.oryxos.core.knowledge.KnowledgeBindingService;
import io.oryxos.core.memory.MemoryService;
import io.oryxos.core.profile.ProfileRegistry;
import io.oryxos.core.session.Message;
import io.oryxos.core.session.Session;
import io.oryxos.core.session.SessionManager;
import io.oryxos.core.skill.AgentSkillBindingService;
import io.oryxos.core.skill.BoundSkillDescriptor;
import io.oryxos.core.skill.SkillCatalog;
import io.oryxos.core.skill.SkillCatalogEntry;
import io.oryxos.web.common.ApiResponse;
import io.oryxos.web.controller.dto.AgentExecutionView;
import io.oryxos.web.controller.dto.AgentKnowledgeBindingsView;
import io.oryxos.web.controller.dto.AgentSkillBindingsView;
import io.oryxos.web.controller.dto.AgentView;
import io.oryxos.web.controller.dto.CreateAgentRequest;
import io.oryxos.web.controller.dto.GenerateFilesRequest;
import io.oryxos.web.controller.dto.GeneratedFilesView;
import io.oryxos.web.controller.dto.ImportAgentRequest;
import io.oryxos.web.controller.dto.ImportPreviewView;
import io.oryxos.web.controller.dto.MessageRequest;
import io.oryxos.web.controller.dto.MessageResponse;
import io.oryxos.web.controller.dto.ReplaceKnowledgeBindingsRequest;
import io.oryxos.web.controller.dto.ReplaceSkillBindingsRequest;
import io.oryxos.web.controller.dto.SaveFilesRequest;
import io.oryxos.web.controller.dto.SessionView;
import io.oryxos.web.controller.dto.TriggerResponse;
import io.oryxos.web.controller.dto.UpdateAgentBasicRequest;
import io.oryxos.web.controller.dto.UpdateAgentRequest;
import io.oryxos.web.controller.dto.UpdatePersonaRequest;
import io.oryxos.web.error.ResourceNotFoundException;
import io.oryxos.web.sse.SseStreamSupport;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Agent 端点（第 26 节的 invoke + 第 30 节的动态管理 CRUD）：generate/create/get/list/update/delete 薄转发给 {@link
 * AgentLifecycleService}；invoke 走 {@link AgentService#processStateless}，不创建持久会话。
 *
 * <p>错误码复用既有：name 冲突 / 定义非法 → 400（`IllegalArgumentException`/`ProfileValidationException`）； 不存在 →
 * 404（`ResourceNotFoundException`）；统一 `ApiResponse` 信封。
 */
@edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
    value = {"SPRING_ENDPOINT", "EI_EXPOSE_REP2"},
    justification =
        "core-stage web API is unauthenticated by design (internal network + gateway); auth is extension-phase. 协作者是 Spring 注入的共享单例，构造注入共享同一引用正是意图。")
@RestController
@RequestMapping("/api/v1/agents")
public class AgentApiController {

  private static final int MAX_MESSAGE_LENGTH = 32 * 1024;
  private static final int MAX_HISTORY_MESSAGES = 100;
  // 管理台「一个 Agent 一个固定会话」：固定 channel+user，profile=Agent 名 → 每个 Agent 恰好一条会话（上下文累积）。
  private static final String CONSOLE_CHANNEL = "admin";
  private static final String CONSOLE_USER = "console";

  private static final int MAX_EXECUTION_HISTORY = 50;
  private static final String TRIGGER_SOURCE_MANUAL = "manual";
  private static final String DEFAULT_TRIGGER_MESSAGE = "请按你的职责执行一次任务。";

  private final AgentLifecycleService lifecycle;
  private final AgentService agentService;
  private final SessionManager sessionManager;
  private final ProfileRegistry profileRegistry;
  private final MemoryService memoryService;
  private final AgentExecutionService executionService;
  private final AgentSkillBindingService skillBindings;
  private final SkillCatalog skillCatalog;
  private final KnowledgeBindingService knowledgeBindings;
  // 025 导入链：容器「tools」Map bean（OryxOsRuntime @Bean），导入器用它做工具名交集；null（测试直构/旧调用点）→ 空集。
  private final Map<String, OryxTool> tools;

  /** SSE 编排（019）：默认实例保 telescoping 构造与测试直构可用，运行时由 @Autowired setter 覆盖为容器单例。 */
  private SseStreamSupport sseStreamSupport = SseStreamSupport.defaultSupport();

  @Autowired
  public void setSseStreamSupport(SseStreamSupport sseStreamSupport) {
    this.sseStreamSupport = sseStreamSupport;
  }

  public AgentApiController(
      AgentLifecycleService lifecycle,
      AgentService agentService,
      SessionManager sessionManager,
      ProfileRegistry profileRegistry,
      MemoryService memoryService,
      AgentExecutionService executionService) {
    this(
        lifecycle,
        agentService,
        sessionManager,
        profileRegistry,
        memoryService,
        executionService,
        null,
        null,
        null);
  }

  public AgentApiController(
      AgentLifecycleService lifecycle,
      AgentService agentService,
      SessionManager sessionManager,
      ProfileRegistry profileRegistry,
      MemoryService memoryService,
      AgentExecutionService executionService,
      AgentSkillBindingService skillBindings) {
    this(
        lifecycle,
        agentService,
        sessionManager,
        profileRegistry,
        memoryService,
        executionService,
        skillBindings,
        null,
        null);
  }

  public AgentApiController(
      AgentLifecycleService lifecycle,
      AgentService agentService,
      SessionManager sessionManager,
      ProfileRegistry profileRegistry,
      MemoryService memoryService,
      AgentExecutionService executionService,
      AgentSkillBindingService skillBindings,
      SkillCatalog skillCatalog) {
    this(
        lifecycle,
        agentService,
        sessionManager,
        profileRegistry,
        memoryService,
        executionService,
        skillBindings,
        skillCatalog,
        null);
  }

  /** 源码兼容旧 9 参调用点（telescoping 链 / 测试直构）：tools 置 null，导入端点退化为空工具交集。 */
  public AgentApiController(
      AgentLifecycleService lifecycle,
      AgentService agentService,
      SessionManager sessionManager,
      ProfileRegistry profileRegistry,
      MemoryService memoryService,
      AgentExecutionService executionService,
      AgentSkillBindingService skillBindings,
      SkillCatalog skillCatalog,
      KnowledgeBindingService knowledgeBindings) {
    this(
        lifecycle,
        agentService,
        sessionManager,
        profileRegistry,
        memoryService,
        executionService,
        skillBindings,
        skillCatalog,
        knowledgeBindings,
        null);
  }

  @Autowired
  public AgentApiController(
      AgentLifecycleService lifecycle,
      AgentService agentService,
      SessionManager sessionManager,
      ProfileRegistry profileRegistry,
      MemoryService memoryService,
      AgentExecutionService executionService,
      AgentSkillBindingService skillBindings,
      SkillCatalog skillCatalog,
      KnowledgeBindingService knowledgeBindings,
      @Qualifier("tools") Map<String, OryxTool> tools) {
    this.lifecycle = lifecycle;
    this.agentService = agentService;
    this.sessionManager = sessionManager;
    this.profileRegistry = profileRegistry;
    this.memoryService = memoryService;
    this.executionService = executionService;
    this.skillBindings = skillBindings;
    this.skillCatalog = skillCatalog;
    this.knowledgeBindings = knowledgeBindings;
    this.tools = tools;
  }

  /** 创建：只需 name + description，后台按模板脚手架出完整目录 + 派生注册（失败回滚）。 */
  @PostMapping
  public ApiResponse<AgentView> create(@RequestBody CreateAgentRequest req) {
    if (req == null || req.name() == null || req.name().isBlank()) {
      throw new IllegalArgumentException("Agent 名为空");
    }
    io.oryxos.core.profile.Profile created =
        lifecycle.create(
            req.name(), req.description(), req.provider(), req.model(), req.skillBindings());
    // 014 FR-018：新建表单的知识库多选在此落软连接（绑定仅管理面动作；失败时 Agent 已建、错误可读可重试）
    if (!req.knowledgeBindings().isEmpty()) {
      requireKnowledgeBindings().replaceBindings(req.name(), req.knowledgeBindings());
    }
    return ApiResponse.ok(view(created));
  }

  @GetMapping
  public ApiResponse<List<AgentView>> list() {
    return ApiResponse.ok(lifecycle.list().stream().map(this::view).toList());
  }

  @GetMapping("/{name}")
  public ApiResponse<AgentView> get(@PathVariable String name) {
    return ApiResponse.ok(
        lifecycle
            .get(name)
            .map(this::view)
            .orElseThrow(() -> new ResourceNotFoundException("Agent 不存在: " + name)));
  }

  @PutMapping("/{name}")
  public ApiResponse<AgentView> update(
      @PathVariable String name, @RequestBody UpdateAgentRequest req) {
    if (lifecycle.get(name).isEmpty()) {
      throw new ResourceNotFoundException("Agent 不存在: " + name); // → 404
    }
    return ApiResponse.ok(view(lifecycle.update(name, req.agentMarkdown())));
  }

  @DeleteMapping("/{name}")
  public ApiResponse<Void> delete(@PathVariable String name) {
    if (lifecycle.get(name).isEmpty()) {
      throw new ResourceNotFoundException("Agent 不存在: " + name); // → 404
    }
    lifecycle.delete(name);
    return ApiResponse.ok(null);
  }

  /**
   * 结构化编辑基本信息（description / provider / model）：只改 AGENT.md frontmatter 的对应 key，正文与其他配置原样保留。Skill 绑定
   * 由专用端点管理，不写回 AGENT.md。 非法定义 → 400（不破坏原文件）；不存在 → 404。
   */
  @PutMapping("/{name}/basic")
  public ApiResponse<AgentView> updateBasic(
      @PathVariable String name, @RequestBody UpdateAgentBasicRequest req) {
    if (lifecycle.get(name).isEmpty()) {
      throw new ResourceNotFoundException("Agent 不存在: " + name); // → 404
    }
    return ApiResponse.ok(
        AgentView.from(
            lifecycle.updateBasicInfo(name, req.description(), req.provider(), req.model())));
  }

  /**
   * 结构化编辑人格（025 persona 段）：只改 AGENT.md frontmatter 的 persona 块，正文与其他配置原样保留。缺 name/role → 400； Agent
   * 不存在 → 404。
   */
  @PutMapping("/{name}/persona")
  public ApiResponse<AgentView> updatePersona(
      @PathVariable String name, @RequestBody UpdatePersonaRequest req) {
    if (lifecycle.get(name).isEmpty()) {
      throw new ResourceNotFoundException("Agent 不存在: " + name); // → 404
    }
    io.oryxos.core.profile.Profile.Persona persona =
        req == null
            ? null
            : new io.oryxos.core.profile.Profile.Persona(
                req.name(),
                req.role(),
                req.traits(),
                req.tone(),
                req.values(),
                req.boundaries(),
                req.sampleStyle());
    return ApiResponse.ok(view(lifecycle.updatePersona(name, persona)));
  }

  /** 019：Accept 含 text/event-stream 时 SSE 流式（校验前置，FR-009）；否则一次性 JSON 路径零改动。 */
  @PostMapping("/{name}/invoke")
  public ApiResponse<MessageResponse> invoke(
      @PathVariable String name,
      @RequestBody MessageRequest req,
      HttpServletRequest request,
      HttpServletResponse response) {
    if (req == null || req.content() == null || req.content().isEmpty()) {
      throw new IllegalArgumentException("消息为空"); // → 400
    }
    if (req.content().length() > MAX_MESSAGE_LENGTH) {
      throw new IllegalArgumentException("消息超过 32KB 上限"); // → 400
    }
    requireAgent(name);
    // 021：controller 先 open 拿 ID 回传调用方；AgentService 兜底 openIfAbsent 复用同一 ID
    try (TraceContext.Scope traceScope = TraceContext.openIfAbsent()) {
      if (SseStreamSupport.wantsEventStream(request)) {
        String content = req.content();
        sseStreamSupport.stream(
            response, listener -> agentService.processStateless(name, content, listener));
        return null; // 响应已由 SSE 流写出并提交（trace 事件由 SseStreamSupport 发出）
      }
      String reply = agentService.processStateless(name, req.content());
      return ApiResponse.ok(new MessageResponse(reply, traceScope.traceId()));
    }
  }

  /** 这个 Agent 的专属长期记忆（30 节：记忆跟着 Agent 走）。 */
  @GetMapping("/{name}/memory")
  public ApiResponse<String> memory(@PathVariable String name) {
    requireAgent(name);
    return ApiResponse.ok(memoryService.readAll(name));
  }

  /** 这个 Agent 的固定管理台会话（getOrCreate 幂等 → 恒为同一条，历史自动恢复）。 */
  @GetMapping("/{name}/session")
  public ApiResponse<SessionView> consoleSession(@PathVariable String name) {
    requireAgent(name);
    Session session = sessionManager.getOrCreate(CONSOLE_CHANNEL, CONSOLE_USER, name);
    return ApiResponse.ok(
        new SessionView(session.sessionId(), session.profileName(), recent(session.messages())));
  }

  /**
   * 往固定管理台会话发一条消息，触发 ReAct（同 invoke 入口，但落在这个 Agent 的固定会话里，累积上下文）。
   * 019：管理台聊天页的流式载体端点（FR-013/R5），Accept 分流规则与 invoke 一致。
   */
  @PostMapping("/{name}/session/messages")
  public ApiResponse<MessageResponse> consoleSend(
      @PathVariable String name,
      @RequestBody MessageRequest req,
      HttpServletRequest request,
      HttpServletResponse response) {
    if (req == null || req.content() == null || req.content().isEmpty()) {
      throw new IllegalArgumentException("消息为空"); // → 400
    }
    if (req.content().length() > MAX_MESSAGE_LENGTH) {
      throw new IllegalArgumentException("消息超过 32KB 上限"); // → 400
    }
    requireAgent(name);
    Session session = sessionManager.getOrCreate(CONSOLE_CHANNEL, CONSOLE_USER, name);
    // 021：同 invoke——先 open 回传，流式路径由 SseStreamSupport 发 trace 事件
    try (TraceContext.Scope traceScope = TraceContext.openIfAbsent()) {
      if (SseStreamSupport.wantsEventStream(request)) {
        String content = req.content();
        sseStreamSupport.stream(
            response, listener -> agentService.process(session, content, listener));
        return null; // 响应已由 SSE 流写出并提交
      }
      return ApiResponse.ok(
          new MessageResponse(agentService.process(session, req.content()), traceScope.traceId()));
    }
  }

  /**
   * 立即触发一次（异步）：落一条"运行中"执行记录、**立即返回**（不干等整轮 ReAct → 消除浏览器 Failed to fetch），ReAct 在虚拟线程后台跑，结果进这个
   * Agent 的固定会话、状态回填执行历史。消息缺省用通用触发语。
   */
  @PostMapping("/{name}/trigger")
  public ApiResponse<TriggerResponse> trigger(
      @PathVariable String name, @RequestBody(required = false) MessageRequest req) {
    requireAgent(name);
    String message =
        req == null || req.content() == null || req.content().isBlank()
            ? DEFAULT_TRIGGER_MESSAGE
            : req.content();
    if (message.length() > MAX_MESSAGE_LENGTH) {
      throw new IllegalArgumentException("消息超过 32KB 上限"); // → 400
    }
    Session session = sessionManager.getOrCreate(CONSOLE_CHANNEL, CONSOLE_USER, name);
    long executionId =
        executionService.triggerAsync(
            name,
            TRIGGER_SOURCE_MANUAL,
            session.sessionId(),
            message,
            () -> agentService.process(session, message));
    return ApiResponse.ok(new TriggerResponse(executionId, "RUNNING"));
  }

  /** 该 Agent 的执行历史（手动触发 + 定时触发，起止时间 / 状态 / 时长），按开始时间倒序，最多 50 条。 */
  @GetMapping("/{name}/executions")
  public ApiResponse<List<AgentExecutionView>> executions(@PathVariable String name) {
    requireAgent(name);
    return ApiResponse.ok(
        executionService.history(name, MAX_EXECUTION_HISTORY).stream()
            .map(AgentExecutionView::from)
            .toList());
  }

  /** 用大模型按一句话生成 AGENT.md 草稿（只生成、不落盘、不注册；非法定义 → 400）。 */
  @PostMapping("/{name}/generate-files")
  public ApiResponse<GeneratedFilesView> generateFiles(
      @PathVariable String name, @RequestBody GenerateFilesRequest req) {
    String description = req == null ? null : req.description();
    String notifyChannel = req == null ? null : req.notifyChannel();
    List<String> skills = req == null ? List.of() : req.requiredSkills();
    String provider = req == null ? null : req.provider();
    String model = req == null ? null : req.model();
    return ApiResponse.ok(
        GeneratedFilesView.from(
            lifecycle.generateDraft(name, description, notifyChannel, skills, provider, model)));
  }

  /** 保存（可能被改过的）一组 Agent 文件，写入即生效（AGENT.md 非法 → 400，不写坏目录）。 */
  @PostMapping("/{name}/files")
  public ApiResponse<AgentView> saveFiles(
      @PathVariable String name, @RequestBody SaveFilesRequest req) {
    io.oryxos.core.profile.Profile saved =
        lifecycle.saveFiles(
            name, req == null ? null : req.files(), req == null ? null : req.skillBindings());
    // 014 FR-018：生成/编辑保存时同步知识库绑定（null = 不改动）
    if (req != null && req.knowledgeBindings() != null) {
      requireKnowledgeBindings().replaceBindings(name, req.knowledgeBindings());
    }
    return ApiResponse.ok(view(saved));
  }

  /**
   * 导入预览（025）：解析 agency-agents 源文件 → 渲染成 AGENT.md 全文 + 字段投影 + 派生名 + dry-run 校验，不落盘、不注册。 dry-run
   * 校验不抛——预览永远 200，失败体现在 validation.valid=false + message。
   */
  @PostMapping("/import-preview")
  public ApiResponse<ImportPreviewView> importPreview(@RequestBody ImportAgentRequest req) {
    if (req == null || req.sourceContent() == null || req.sourceContent().isBlank()) {
      throw new IllegalArgumentException("源文件内容为空"); // → 400
    }
    ParsedExpert expert = new AgencyAgentsParser().parse(req.sourceContent());
    String name = resolveImportName(req.name(), expert);
    String rendered =
        new AgencyAgentsImporter()
            .toMarkdown(
                expert,
                resolveImportProvider(req),
                tools == null ? Set.of() : tools.keySet(),
                name,
                req.model());
    AgentValidation validation = lifecycle.validateAgent(name, rendered);
    return ApiResponse.ok(
        new ImportPreviewView(
            name,
            rendered,
            ImportPreviewView.ImportExpertView.from(expert),
            ImportPreviewView.ValidationView.from(validation)));
  }

  /** 导入落地（025）：渲染 + 校验通过 → 真正创建 Agent。同名已有 → 400（只创建不覆盖，先删再导）。 */
  @PostMapping("/import")
  public ApiResponse<AgentView> importAgent(@RequestBody ImportAgentRequest req) {
    if (req == null || req.sourceContent() == null || req.sourceContent().isBlank()) {
      throw new IllegalArgumentException("源文件内容为空"); // → 400
    }
    ParsedExpert expert = new AgencyAgentsParser().parse(req.sourceContent());
    String name = resolveImportName(req.name(), expert);
    String rendered =
        new AgencyAgentsImporter()
            .toMarkdown(
                expert,
                resolveImportProvider(req),
                tools == null ? Set.of() : tools.keySet(),
                name,
                req.model());
    return ApiResponse.ok(view(lifecycle.importAgent(name, rendered)));
  }

  // ---- 知识库绑定三件套 + 整体替换（014 FR-002/018/019：绑定仅管理面动作，运行时无自改工具）----

  @GetMapping("/{name}/knowledge")
  public ApiResponse<AgentKnowledgeBindingsView> knowledge(@PathVariable String name) {
    requireAgent(name);
    return ApiResponse.ok(
        AgentKnowledgeBindingsView.from(requireKnowledgeBindings().inspect(name)));
  }

  @PutMapping("/{name}/knowledge/{kb}")
  public ApiResponse<AgentKnowledgeBindingsView> bindKnowledge(
      @PathVariable String name, @PathVariable String kb) {
    requireAgent(name);
    requireKnowledgeBindings().bind(name, kb);
    return knowledge(name);
  }

  @DeleteMapping("/{name}/knowledge/{kb}")
  public ApiResponse<AgentKnowledgeBindingsView> unbindKnowledge(
      @PathVariable String name, @PathVariable String kb) {
    requireAgent(name);
    requireKnowledgeBindings().unbind(name, kb);
    return knowledge(name);
  }

  @PutMapping("/{name}/knowledge")
  public ApiResponse<AgentKnowledgeBindingsView> replaceKnowledge(
      @PathVariable String name, @RequestBody ReplaceKnowledgeBindingsRequest request) {
    requireAgent(name);
    return ApiResponse.ok(
        AgentKnowledgeBindingsView.from(
            requireKnowledgeBindings()
                .replaceBindings(name, request == null ? List.of() : request.knowledge())));
  }

  @GetMapping("/{name}/skills")
  public ApiResponse<AgentSkillBindingsView> skills(@PathVariable String name) {
    requireAgent(name);
    return ApiResponse.ok(AgentSkillBindingsView.from(requireBindings().inspect(name)));
  }

  @PutMapping("/{name}/skills/{skill}")
  public ApiResponse<AgentSkillBindingsView> bind(
      @PathVariable String name, @PathVariable String skill) {
    requireAgent(name);
    requireSkillsExist(List.of(skill));
    validateCatalog(List.of(skill));
    requireBindings().bind(name, skill);
    return skills(name);
  }

  @DeleteMapping("/{name}/skills/{skill}")
  public ApiResponse<AgentSkillBindingsView> unbind(
      @PathVariable String name, @PathVariable String skill) {
    requireAgent(name);
    requireBindings().unbind(name, skill);
    return skills(name);
  }

  @PutMapping("/{name}/skills")
  public ApiResponse<AgentSkillBindingsView> replaceSkills(
      @PathVariable String name, @RequestBody ReplaceSkillBindingsRequest request) {
    requireAgent(name);
    List<String> desired = request == null ? List.of() : request.skills();
    requireSkillsExist(desired);
    validateCatalog(desired);
    return ApiResponse.ok(
        AgentSkillBindingsView.from(requireBindings().replaceBindings(name, desired)));
  }

  private AgentView view(io.oryxos.core.profile.Profile profile) {
    List<String> skills =
        skillBindings == null
            ? List.of()
            : skillBindings.inspect(profile.name()).bindings().stream()
                .map(BoundSkillDescriptor::name)
                .toList();
    return AgentView.from(profile, skills);
  }

  private void requireAgent(String name) {
    if (profileRegistry.get(name).isEmpty()) {
      throw new ResourceNotFoundException("Agent 不存在: " + name);
    }
  }

  private AgentSkillBindingService requireBindings() {
    if (skillBindings == null) {
      throw new IllegalStateException("Agent Skill 绑定服务未装配");
    }
    return skillBindings;
  }

  private KnowledgeBindingService requireKnowledgeBindings() {
    if (knowledgeBindings == null) {
      throw new IllegalStateException("Agent 知识库绑定服务未装配");
    }
    return knowledgeBindings;
  }

  private void requireSkillsExist(List<String> names) {
    if (names == null) {
      return;
    }
    AgentSkillBindingService bindings = requireBindings();
    for (String name : names) {
      if (!bindings.skillExists(name)) {
        throw new ResourceNotFoundException("Skill 不存在: " + name);
      }
    }
  }

  private void validateCatalog(List<String> names) {
    if (names == null || names.isEmpty()) {
      return;
    }
    if (skillCatalog == null) {
      throw new IllegalStateException("Skill catalog 不可用");
    }
    Map<String, SkillCatalogEntry> candidates = new LinkedHashMap<>();
    for (SkillCatalogEntry entry : skillCatalog.query("", null)) {
      if (candidates.putIfAbsent(entry.name(), entry) != null) {
        throw new IllegalArgumentException("Skill catalog 存在同名公共/私有冲突: " + entry.name());
      }
    }
    for (String name : names) {
      SkillCatalogEntry entry = candidates.get(name);
      if (entry == null || !entry.installed()) {
        throw new IllegalArgumentException("Skill 不在可访问且已安装的 catalog 中: " + name);
      }
    }
  }

  /** 025 导入：显式 name 优先；缺省从源 displayName 去掉非 {@code [A-Za-z0-9_-]} 字符派生（中文展示名不进 profile 名）。 */
  private static String resolveImportName(String name, ParsedExpert expert) {
    String n = name == null ? "" : name.strip();
    if (!n.isEmpty()) {
      return n;
    }
    String slug =
        expert.displayName() == null ? "" : expert.displayName().replaceAll("[^A-Za-z0-9_-]", "");
    if (slug.isEmpty()) {
      throw new IllegalArgumentException("无法从源文件派生 Agent 名，请显式提供 name"); // → 400
    }
    return slug;
  }

  /** 导入用 provider：请求显式选择优先（导入弹框有 provider 下拉）；未选才跟随底座默认 provider。 */
  private String resolveImportProvider(ImportAgentRequest req) {
    return req.provider() == null || req.provider().isBlank()
        ? lifecycle.defaultProvider()
        : req.provider();
  }

  private static List<Message> recent(List<Message> messages) {
    if (messages.size() <= MAX_HISTORY_MESSAGES) {
      return messages;
    }
    return messages.subList(messages.size() - MAX_HISTORY_MESSAGES, messages.size());
  }
}
