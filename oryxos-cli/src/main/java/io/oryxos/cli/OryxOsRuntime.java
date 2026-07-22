package io.oryxos.cli;

import io.oryxos.channel.cli.CliChannel;
import io.oryxos.core.OryxTool;
import io.oryxos.core.agent.AgentLifecycleService;
import io.oryxos.core.agent.AgentLoader;
import io.oryxos.core.agent.AgentScheduler;
import io.oryxos.core.agent.AgentService;
import io.oryxos.core.agent.AgentStore;
import io.oryxos.core.agent.PromptBuilder;
import io.oryxos.core.agent.ReActLoop;
import io.oryxos.core.agent.ScheduledTaskStore;
import io.oryxos.core.agent.ToolExecutor;
import io.oryxos.core.agent.ToolInvocationAuditor;
import io.oryxos.core.agent.WorkspaceWatcher;
import io.oryxos.core.context.ContextLoader;
import io.oryxos.core.memory.MemoryService;
import io.oryxos.core.notify.NotifyChannelRegistry;
import io.oryxos.core.profile.ProfileRegistry;
import io.oryxos.core.provider.LlmCallAuditor;
import io.oryxos.core.provider.ProviderDef;
import io.oryxos.core.provider.ProviderRegistry;
import io.oryxos.core.provider.ProviderService;
import io.oryxos.core.sandbox.SandboxWhitelist.Category;
import io.oryxos.core.sandbox.SandboxWhitelistStore;
import io.oryxos.core.session.SessionManager;
import io.oryxos.memory.LongTermMemoryStore;
import io.oryxos.memory.MarkdownMemoryStore;
import io.oryxos.memory.Mem0MemoryStore;
import io.oryxos.memory.MemoryServiceImpl;
import io.oryxos.memory.SqliteMemoryStore;
import io.oryxos.memory.builtin.MemoryTools;
import io.oryxos.provider.ProviderChatModelFactory;
import io.oryxos.provider.ProvidersProperties;
import io.oryxos.provider.SpringAiProviderServiceImpl;
import io.oryxos.provider.ToolSchemaAdapter;
import io.oryxos.storage.JpaLlmCallAuditor;
import io.oryxos.storage.JpaNotifyChannelRegistry;
import io.oryxos.storage.JpaProviderRegistry;
import io.oryxos.storage.JpaSandboxWhitelistStore;
import io.oryxos.storage.JpaScheduledTaskStore;
import io.oryxos.storage.JpaSessionManager;
import io.oryxos.storage.JpaToolInvocationAuditor;
import io.oryxos.storage.LlmCallRepository;
import io.oryxos.storage.LlmProviderRepository;
import io.oryxos.storage.MemoryEntryRepository;
import io.oryxos.storage.NotifyChannelRepository;
import io.oryxos.storage.SandboxWhitelistRepository;
import io.oryxos.storage.ScheduledTaskRepository;
import io.oryxos.storage.SessionRepository;
import io.oryxos.storage.TaskExecutionRepository;
import io.oryxos.storage.ToolInvocationRepository;
import io.oryxos.tool.ToolRegistry;
import io.oryxos.tool.builtin.FileTools;
import io.oryxos.tool.builtin.HttpTools;
import io.oryxos.tool.builtin.InteractionTools;
import io.oryxos.tool.builtin.NotifyTools;
import io.oryxos.tool.builtin.ShellTools;
import io.oryxos.tool.builtin.WebSearchTools;
import io.oryxos.tool.interaction.ConsoleUserInteraction;
import io.oryxos.tool.mcp.McpClientService;
import io.oryxos.tool.mcp.McpConfigLoader;
import io.oryxos.tool.notify.DingTalkNotifyAdapter;
import io.oryxos.tool.notify.FeishuNotifyAdapter;
import io.oryxos.tool.notify.NotifyChannelAdapter;
import io.oryxos.tool.notify.WeComNotifyAdapter;
import io.oryxos.tool.notify.WebhookNotifyAdapter;
import io.oryxos.tool.sandbox.FileSandboxProperties;
import io.oryxos.tool.sandbox.HttpSandboxProperties;
import io.oryxos.tool.sandbox.Sandbox;
import io.oryxos.tool.sandbox.ShellSandboxProperties;
import io.oryxos.tool.sandbox.WhitelistSandbox;
import io.oryxos.tool.web.DuckDuckGoSearchProvider;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.client.RestClient;

/**
 * 重命令（chat/serve/gateway）的 Spring 装配。轻命令不进这里（课件坑二：为列个目录不值得等 4 秒）。
 *
 * <p>课件坑四：{@code scanBasePackages} 只管普通 Bean，不会带动 JPA 仓库与实体扫描跟着跨模块——
 * 存储在独立模块（io.oryxos.storage），必须显式 @EnableJpaRepositories + @EntityScan， 否则 "Found 0 JPA repository
 * interfaces"，审计与会话静默写不进去。
 *
 * <p>运行链全部 @Bean 显式装配：16/17 节交付的类保持纯 POJO 零框架依赖；Provider 显式映射（宪法 III）。
 */
@SpringBootApplication(scanBasePackages = "io.oryxos")
@EnableJpaRepositories(basePackages = "io.oryxos.storage")
@EntityScan(basePackages = "io.oryxos.storage")
@EnableConfigurationProperties({
  ProvidersProperties.class,
  FileSandboxProperties.class,
  ShellSandboxProperties.class,
  HttpSandboxProperties.class
})
public class OryxOsRuntime {

  // 工作区根目录默认 ./.oryxos；可用属性 oryxos.root 覆盖（集成测试指向临时工作区，默认行为不变）。
  // 从 Spring Environment 解析（而非 JVM 静态捕获 System property）：使每个上下文各持自己的根，
  // 支持同一 JVM 内多套 hermetic 测试上下文并存（各自的 @DynamicPropertySource / SpringApplicationBuilder 根互不干扰）。
  @Value("${oryxos.root:.oryxos}")
  private String oryxosRootProp;

  private Path oryxosRoot() {
    return Path.of(oryxosRootProp);
  }

  /** 31 节：Provider 动态注册表（SQLite）。启动把 config 的 oryxos.providers 播种进 DB（库里没有才写），之后以 DB 为准。 */
  @Bean
  ProviderRegistry providerRegistry(
      LlmProviderRepository repository, ProvidersProperties properties) {
    ProviderRegistry registry = new JpaProviderRegistry(repository);
    properties.validate(); // config providers 缺失/非法仍启动即点名报错，不静默失败
    for (ProvidersProperties.ProviderConfig c : properties.providers()) {
      if (!registry.exists(c.name())) {
        registry.save(new ProviderDef(c.name(), c.apiKey(), c.baseUrl(), null));
      }
    }
    return registry;
  }

  @Bean
  LlmCallAuditor llmCallAuditor(LlmCallRepository repository) {
    return new JpaLlmCallAuditor(repository);
  }

  @Bean
  ToolInvocationAuditor toolInvocationAuditor(ToolInvocationRepository repository) {
    return new JpaToolInvocationAuditor(repository);
  }

  @Bean
  ProviderService providerService(ProviderRegistry providerRegistry, LlmCallAuditor auditor) {
    // 动态解析（31 节）：按名从注册表取参数、经工厂即时建/缓存 ChatModel（宪法 III 显式映射，只是运行时可变）
    ProviderChatModelFactory factory = new ProviderChatModelFactory();
    return new SpringAiProviderServiceImpl(
        providerRegistry,
        def -> factory.buildOne(def.name(), def.apiKey(), def.baseUrl()),
        new ToolSchemaAdapter(),
        auditor);
  }

  @Bean
  AgentLoader agentLoader(ProviderRegistry providerRegistry, Map<String, OryxTool> tools) {
    // 29 节：一个目录 = 一个 Agent——扫 .oryxos/agents/ 逐个 AGENT.md 派生 Profile。
    // provider 名单用注册表实时视图：运行时新增 provider 后，新建/改的 Agent 立刻能引用它（不拍照）。
    return new AgentLoader(
        oryxosRoot().resolve("agents"), liveProviderNames(providerRegistry), tools.keySet());
  }

  /** provider 名的实时视图（backed by 注册表）：增删 provider 立即反映到 Agent 派生校验。 */
  private static java.util.Set<String> liveProviderNames(ProviderRegistry registry) {
    return new java.util.AbstractSet<>() {
      @Override
      public boolean contains(Object o) {
        return (o instanceof String) && registry.exists((String) o);
      }

      @Override
      public java.util.Iterator<String> iterator() {
        return registry.list().stream().map(ProviderDef::name).iterator();
      }

      @Override
      public int size() {
        return registry.list().size();
      }
    };
  }

  @Bean
  ProfileRegistry profileRegistry(AgentLoader agentLoader) {
    // 启动全量扫；30 节 WorkspaceWatcher 负责启动后的实时变更（同一段 register）
    return agentLoader.loadAll();
  }

  @Bean
  AgentStore agentStore() {
    return new AgentStore(oryxosRoot());
  }

  /** 30 节：Agent 生命周期编排。创建脚手架的 AGENT.md 模板里 provider 缺省取 oryxos.providers 第一个（保证可注册）。 */
  @Bean
  AgentLifecycleService agentLifecycleService(
      AgentLoader agentLoader,
      ProfileRegistry profileRegistry,
      AgentScheduler agentScheduler,
      AgentStore agentStore,
      ProviderService providerService,
      ProvidersProperties providers,
      @Value("${oryxos.author.provider:}") String authorProvider,
      @Value("${oryxos.author.model:}") String authorModel) {
    String defaultProvider =
        providers.providers().isEmpty() ? null : providers.providers().get(0).name();
    // 生成用 provider 缺省取 providers 第一个（宪法 III 显式映射的 key）
    String genProvider =
        authorProvider == null || authorProvider.isBlank() ? defaultProvider : authorProvider;
    return new AgentLifecycleService(
        agentLoader,
        profileRegistry,
        agentScheduler,
        agentStore,
        providerService,
        defaultProvider,
        genProvider,
        authorModel);
  }

  /** 30 节 WorkspaceWatcher 专用守护线程执行器（跟 25 节调度线程池同类，不手工 new Thread）。 */
  @Bean
  ThreadPoolTaskExecutor workspaceWatcherExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(1);
    executor.setMaxPoolSize(1);
    executor.setThreadNamePrefix("oryxos-workspace-watcher-");
    executor.setDaemon(true); // chat 跑完进程要能退出；serve/gateway 常驻靠主线程保活
    executor.initialize();
    return executor;
  }

  /** 30 节：实时监听 .oryxos/agents/——守护线程上跑监听循环，启动后的变更走同一段 register。 */
  @Bean(initMethod = "start")
  WorkspaceWatcher workspaceWatcher(
      AgentLifecycleService agentLifecycleService,
      ThreadPoolTaskExecutor workspaceWatcherExecutor) {
    return new WorkspaceWatcher(agentLifecycleService, oryxosRoot(), workspaceWatcherExecutor);
  }

  @Bean
  ContextLoader contextLoader() {
    return new ContextLoader(oryxosRoot());
  }

  /** 31 节：Sandbox 白名单持久化（SQLite）。运行时增删写穿落库、重启保留。 */
  @Bean
  SandboxWhitelistStore sandboxWhitelistStore(SandboxWhitelistRepository repository) {
    return new JpaSandboxWhitelistStore(repository);
  }

  @Bean
  WhitelistSandbox sandbox(
      SandboxWhitelistStore whitelistStore,
      FileSandboxProperties fileProps,
      ShellSandboxProperties shellProps,
      HttpSandboxProperties httpProps) {
    // 24 节：真正的白名单校验（宪法 VI 第一档）。空列表 = deny-all。
    // 返回具体类型（而非 Sandbox 接口）：同一实例既是校验墙 Sandbox 又是可管理白名单 SandboxWhitelist，
    // 具体类型让 Spring 同时按两个接口装配（工具注 Sandbox，Web 管理端点注 SandboxWhitelist）。
    // 31 节：从库恢复已落库的三类白名单；运行时增删由 WhitelistSandbox 写穿落库。
    WhitelistSandbox whitelist = new WhitelistSandbox(whitelistStore);
    // 启动播种：把 config/application.yml 的三类白名单插进来（经 add → 幂等 + 落库；库里已有的不重复）。
    // 通过 add 而非直接写库，确保 FILE 的规范形（绝对路径）与 list/删除对齐。
    nullToEmpty(fileProps.allowedPaths()).forEach(p -> whitelist.add(Category.FILE, p));
    nullToEmpty(shellProps.allowedCommands()).forEach(c -> whitelist.add(Category.SHELL, c));
    nullToEmpty(httpProps.allowedDomains()).forEach(d -> whitelist.add(Category.HTTP, d));
    // 工作区根永远是 Agent 的家：随 oryxos.root 自动纳入文件白名单（幂等 + 落库）。
    whitelist.add(Category.FILE, oryxosRootProp);
    return whitelist;
  }

  private static List<String> nullToEmpty(List<String> list) {
    return list == null ? List.of() : list;
  }

  @Bean
  RestClient restClient() {
    return RestClient.create();
  }

  /** 长期记忆后端：按 memory.backend 选一档（默认 markdown）——这是第 21/22 节"接口墙"的装配落点。 */
  @Bean
  LongTermMemoryStore longTermMemoryStore(
      @org.springframework.beans.factory.annotation.Value("${memory.backend:markdown}")
          String backend,
      MemoryEntryRepository memoryEntryRepository,
      RestClient restClient,
      @org.springframework.beans.factory.annotation.Value("${memory.mem0.base-url:}")
          String mem0BaseUrl,
      @org.springframework.beans.factory.annotation.Value("${memory.mem0.user-id:oryxos}")
          String mem0UserId) {
    return switch (backend) {
      case "sqlite" -> new SqliteMemoryStore(memoryEntryRepository);
      case "mem0" ->
          new Mem0MemoryStore(restClient.mutate().baseUrl(mem0BaseUrl).build(), mem0UserId);
      default -> new MarkdownMemoryStore(oryxosRoot());
    };
  }

  @Bean
  MemoryService memoryService(LongTermMemoryStore store) {
    return new MemoryServiceImpl(store);
  }

  @Bean
  ToolRegistry toolRegistry(
      Sandbox sandbox,
      RestClient restClient,
      MemoryService memoryService,
      NotifyChannelRegistry notifyChannelRegistry) {
    ToolRegistry registry = new ToolRegistry();
    // 内置工具走 @Tool 注解管道（schema 自动生成，宪法 II 第二件事）
    registry.registerAnnotated(new FileTools(sandbox)); // read/write/list/edit/grep/glob
    registry.registerAnnotated(new ShellTools(sandbox));
    registry.registerAnnotated(new HttpTools(sandbox, restClient));
    registry.registerAnnotated(
        new WebSearchTools(sandbox, new DuckDuckGoSearchProvider(restClient)));
    // chat 是交互终端，ask_user 读控制台；serve/gateway 无人值守时应换 UnsupportedUserInteraction
    registry.registerAnnotated(new InteractionTools(new ConsoleUserInteraction()));
    // notify（19 节 OryxTool 形态）直接注册——渠道实现按 channelType 路由
    Map<String, NotifyChannelAdapter> notifyAdapters =
        Map.of(
            "webhook", new WebhookNotifyAdapter(restClient),
            "wecom", new WeComNotifyAdapter(restClient),
            "feishu", new FeishuNotifyAdapter(restClient),
            "dingtalk", new DingTalkNotifyAdapter(restClient));
    registry.register(new NotifyTools(notifyAdapters, sandbox, notifyChannelRegistry));
    // 记忆工具：save_memory / recall_memory（补齐 20 节预留的两工具面），只认门面对后端无感
    registry.registerAnnotated(new MemoryTools(memoryService));
    // MCP：失联的 server 只 WARN 跳过，不拖垮启动
    new McpClientService(new McpConfigLoader(oryxosRoot().resolve("mcp_servers.yaml")))
        .connectAll(registry);
    return registry;
  }

  @Bean
  Map<String, OryxTool> tools(ToolRegistry toolRegistry) {
    // 20 节起：全部来源统一经 ToolRegistry 供给（PromptBuilder 按 Profile.tools 过滤）
    return toolRegistry.asMap();
  }

  @Bean
  PromptBuilder promptBuilder(
      ContextLoader contextLoader, Map<String, OryxTool> tools, MemoryService memoryService) {
    // 22 节起：注入 MemoryService，长期记忆段由门面供给（会话历史段仍由 PromptBuilder 独立负责）
    return new PromptBuilder(
        contextLoader, tools, memoryService, java.time.Clock.systemDefaultZone());
  }

  @Bean
  ToolExecutor toolExecutor(Map<String, OryxTool> tools, ToolInvocationAuditor auditor) {
    return new ToolExecutor(tools, auditor);
  }

  @Bean
  ReActLoop reActLoop(
      PromptBuilder promptBuilder, ProviderService providerService, ToolExecutor toolExecutor) {
    return new ReActLoop(promptBuilder, providerService, toolExecutor);
  }

  @Bean
  SessionManager sessionManager(SessionRepository repository) {
    return new JpaSessionManager(repository);
  }

  @Bean
  NotifyChannelRegistry notifyChannelRegistry(NotifyChannelRepository repository) {
    return new JpaNotifyChannelRegistry(repository);
  }

  @Bean
  AgentService agentService(
      ProfileRegistry profileRegistry,
      ReActLoop reActLoop,
      SessionManager sessionManager,
      MemoryService memoryService) {
    return new AgentService(profileRegistry, reActLoop, sessionManager, memoryService);
  }

  @Bean
  CliChannel cliChannel(AgentService agentService, SessionManager sessionManager) {
    return new CliChannel(agentService, sessionManager);
  }

  /**
   * 定时任务的调度线程池（25 节）。setDaemon(true)：chat 是一次性命令，跑完对话进程应正常退出——非 daemon 的调度线程会挂住 JVM 不退出（spec Edge
   * Case）；serve/gateway 常驻时靠主线程 join 保活，daemon 调度线程照跑。
   */
  @Bean
  ThreadPoolTaskScheduler taskScheduler() {
    ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(2);
    scheduler.setThreadNamePrefix("oryxos-sched-");
    scheduler.setDaemon(true);
    scheduler.initialize();
    return scheduler;
  }

  /** 28 节：定时任务状态与执行历史落 SQLite（重启不丢），并支撑管理台的查看/立即执行/启用停用。 */
  @Bean
  ScheduledTaskStore scheduledTaskStore(
      ScheduledTaskRepository taskRepository, TaskExecutionRepository executionRepository) {
    return new JpaScheduledTaskStore(taskRepository, executionRepository);
  }

  /** 第三触发源"钟推"（25 节）：initMethod=registerAll 启动即扫描所有 Profile.schedules 逐条注册。 */
  @Bean(initMethod = "registerAll")
  AgentScheduler agentScheduler(
      ThreadPoolTaskScheduler taskScheduler,
      ProfileRegistry profileRegistry,
      AgentService agentService,
      SessionManager sessionManager,
      ScheduledTaskStore scheduledTaskStore) {
    return new AgentScheduler(
        taskScheduler, profileRegistry, agentService, sessionManager, scheduledTaskStore);
  }
}
