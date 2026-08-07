package io.oryxos.web.controller;

import io.oryxos.core.agent.AgentExecutionService;
import io.oryxos.core.agent.AgentLifecycleService;
import io.oryxos.core.agent.AgentService;
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
import io.oryxos.web.controller.dto.AgentSkillBindingsView;
import io.oryxos.web.controller.dto.AgentView;
import io.oryxos.web.controller.dto.CreateAgentRequest;
import io.oryxos.web.controller.dto.GenerateFilesRequest;
import io.oryxos.web.controller.dto.GeneratedFilesView;
import io.oryxos.web.controller.dto.MessageRequest;
import io.oryxos.web.controller.dto.MessageResponse;
import io.oryxos.web.controller.dto.ReplaceSkillBindingsRequest;
import io.oryxos.web.controller.dto.SaveFilesRequest;
import io.oryxos.web.controller.dto.SessionView;
import io.oryxos.web.controller.dto.TriggerResponse;
import io.oryxos.web.controller.dto.UpdateAgentBasicRequest;
import io.oryxos.web.controller.dto.UpdateAgentRequest;
import io.oryxos.web.error.ResourceNotFoundException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
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
      SkillCatalog skillCatalog) {
    this.lifecycle = lifecycle;
    this.agentService = agentService;
    this.sessionManager = sessionManager;
    this.profileRegistry = profileRegistry;
    this.memoryService = memoryService;
    this.executionService = executionService;
    this.skillBindings = skillBindings;
    this.skillCatalog = skillCatalog;
  }

  /** 创建：只需 name + description，后台按模板脚手架出完整目录 + 派生注册（失败回滚）。 */
  @PostMapping
  public ApiResponse<AgentView> create(@RequestBody CreateAgentRequest req) {
    if (req == null || req.name() == null || req.name().isBlank()) {
      throw new IllegalArgumentException("Agent 名为空");
    }
    return ApiResponse.ok(
        view(
            lifecycle.create(
                req.name(), req.description(), req.provider(), req.model(), req.skillBindings())));
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

  @PostMapping("/{name}/invoke")
  public ApiResponse<MessageResponse> invoke(
      @PathVariable String name, @RequestBody MessageRequest req) {
    if (req == null || req.content() == null || req.content().isEmpty()) {
      throw new IllegalArgumentException("消息为空"); // → 400
    }
    if (req.content().length() > MAX_MESSAGE_LENGTH) {
      throw new IllegalArgumentException("消息超过 32KB 上限"); // → 400
    }
    requireAgent(name);
    String reply = agentService.processStateless(name, req.content());
    return ApiResponse.ok(new MessageResponse(reply));
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

  /** 往固定管理台会话发一条消息，触发 ReAct（同 invoke 入口，但落在这个 Agent 的固定会话里，累积上下文）。 */
  @PostMapping("/{name}/session/messages")
  public ApiResponse<MessageResponse> consoleSend(
      @PathVariable String name, @RequestBody MessageRequest req) {
    if (req == null || req.content() == null || req.content().isEmpty()) {
      throw new IllegalArgumentException("消息为空"); // → 400
    }
    if (req.content().length() > MAX_MESSAGE_LENGTH) {
      throw new IllegalArgumentException("消息超过 32KB 上限"); // → 400
    }
    requireAgent(name);
    Session session = sessionManager.getOrCreate(CONSOLE_CHANNEL, CONSOLE_USER, name);
    return ApiResponse.ok(new MessageResponse(agentService.process(session, req.content())));
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
    return ApiResponse.ok(
        view(
            lifecycle.saveFiles(
                name, req == null ? null : req.files(), req == null ? null : req.skillBindings())));
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

  private static List<Message> recent(List<Message> messages) {
    if (messages.size() <= MAX_HISTORY_MESSAGES) {
      return messages;
    }
    return messages.subList(messages.size() - MAX_HISTORY_MESSAGES, messages.size());
  }
}
