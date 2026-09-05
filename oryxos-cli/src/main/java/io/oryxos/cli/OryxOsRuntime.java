package io.oryxos.cli;

import io.oryxos.channel.cli.CliChannel;
import io.oryxos.core.OryxTool;
import io.oryxos.core.agent.AgentExecutionService;
import io.oryxos.core.agent.AgentExecutionStore;
import io.oryxos.core.agent.AgentLifecycleService;
import io.oryxos.core.agent.AgentLoader;
import io.oryxos.core.agent.AgentScheduler;
import io.oryxos.core.agent.AgentService;
import io.oryxos.core.agent.AgentStore;
import io.oryxos.core.agent.InterruptManager;
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
import io.oryxos.core.provider.PricingStore;
import io.oryxos.core.provider.ProviderDef;
import io.oryxos.core.provider.ProviderRegistry;
import io.oryxos.core.provider.ProviderService;
import io.oryxos.core.sandbox.SandboxWhitelist.Category;
import io.oryxos.core.sandbox.SandboxWhitelistStore;
import io.oryxos.core.session.SessionManager;
import io.oryxos.core.skill.AgentSkillBindingService;
import io.oryxos.core.skill.AgentSkillMigrationService;
import io.oryxos.core.skill.AgentSkillStartupReport;
import io.oryxos.core.skill.InstalledSkillCatalog;
import io.oryxos.core.skill.SkillCatalog;
import io.oryxos.core.skill.SkillLoader;
import io.oryxos.core.skill.SkillRegistry;
import io.oryxos.core.skill.SkillService;
import io.oryxos.core.skill.SkillStore;
import io.oryxos.memory.LongTermMemoryStore;
import io.oryxos.memory.MarkdownMemoryStore;
import io.oryxos.memory.Mem0MemoryStore;
import io.oryxos.memory.MemoryServiceImpl;
import io.oryxos.memory.SqliteMemoryStore;
import io.oryxos.memory.builtin.MemoryTools;
import io.oryxos.persona.PersonaPresetCatalog;
import io.oryxos.persona.PersonaService;
import io.oryxos.persona.PersonaStore;
import io.oryxos.provider.ProviderChatModelFactory;
import io.oryxos.provider.ProviderRegistryBootstrap;
import io.oryxos.provider.ProviderRegistryValidator;
import io.oryxos.provider.ProvidersProperties;
import io.oryxos.provider.SpringAiProviderServiceImpl;
import io.oryxos.provider.ToolSchemaAdapter;
import io.oryxos.storage.AgentExecutionRepository;
import io.oryxos.storage.ApiKeyRepository;
import io.oryxos.storage.ApiKeyService;
import io.oryxos.storage.JpaAgentExecutionStore;
import io.oryxos.storage.JpaLlmCallAuditor;
import io.oryxos.storage.JpaNotifyChannelRegistry;
import io.oryxos.storage.JpaPricingStore;
import io.oryxos.storage.JpaProviderRegistry;
import io.oryxos.storage.JpaSandboxWhitelistStore;
import io.oryxos.storage.JpaScheduledTaskStore;
import io.oryxos.storage.JpaSessionManager;
import io.oryxos.storage.JpaToolInvocationAuditor;
import io.oryxos.storage.LlmCallRepository;
import io.oryxos.storage.LlmPricingRepository;
import io.oryxos.storage.LlmProviderRepository;
import io.oryxos.storage.MemoryEntryRepository;
import io.oryxos.storage.NotifyChannelRepository;
import io.oryxos.storage.SandboxWhitelistRepository;
import io.oryxos.storage.ScheduledTaskRepository;
import io.oryxos.storage.SessionRepository;
import io.oryxos.storage.TaskExecutionRepository;
import io.oryxos.storage.ToolInvocationRepository;
import io.oryxos.storage.WebSessionRepository;
import io.oryxos.storage.WebSessionService;
import io.oryxos.storage.WebUserRepository;
import io.oryxos.storage.WebUserService;
import io.oryxos.tool.ToolRegistry;
import io.oryxos.tool.builtin.FileTools;
import io.oryxos.tool.builtin.FormatTools;
import io.oryxos.tool.builtin.HttpTools;
import io.oryxos.tool.builtin.InteractionTools;
import io.oryxos.tool.builtin.NotifyTools;
import io.oryxos.tool.builtin.ShellTools;
import io.oryxos.tool.builtin.UtilTools;
import io.oryxos.tool.builtin.WebSearchTools;
import io.oryxos.tool.interaction.ConsoleUserInteraction;
import io.oryxos.tool.interaction.UnsupportedUserInteraction;
import io.oryxos.tool.interaction.UserInteraction;
import io.oryxos.tool.mcp.McpClientService;
import io.oryxos.tool.mcp.McpConfigLoader;
import io.oryxos.tool.notify.DingTalkNotifyAdapter;
import io.oryxos.tool.notify.EmailNotifyAdapter;
import io.oryxos.tool.notify.FeishuNotifyAdapter;
import io.oryxos.tool.notify.NotifyChannelAdapter;
import io.oryxos.tool.notify.NotifyPoster;
import io.oryxos.tool.notify.WeComNotifyAdapter;
import io.oryxos.tool.notify.WebhookNotifyAdapter;
import io.oryxos.tool.sandbox.CidfileProcessWrapper;
import io.oryxos.tool.sandbox.DockerProcessStarter;
import io.oryxos.tool.sandbox.ExecutionBackendProperties;
import io.oryxos.tool.sandbox.FileSandboxProperties;
import io.oryxos.tool.sandbox.HttpSandboxProperties;
import io.oryxos.tool.sandbox.LocalProcessStarter;
import io.oryxos.tool.sandbox.ProcessStarter;
import io.oryxos.tool.sandbox.Sandbox;
import io.oryxos.tool.sandbox.ShellSandboxProperties;
import io.oryxos.tool.sandbox.SmtpSandboxProperties;
import io.oryxos.tool.sandbox.WhitelistSandbox;
import io.oryxos.tool.sandbox.WorkspacePathMapper;
import io.oryxos.tool.web.DuckDuckGoSearchProvider;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.client.RestClient;
import org.springframework.web.context.WebApplicationContext;

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
  HttpSandboxProperties.class,
  SmtpSandboxProperties.class,
  ExecutionBackendProperties.class
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

  @Bean
  ProviderRegistryValidator providerRegistryValidator() {
    return new ProviderRegistryValidator();
  }

  @Bean
  ProviderRegistryBootstrap providerRegistryBootstrap(ProviderRegistryValidator validator) {
    return new ProviderRegistryBootstrap(validator);
  }

  /** 31 节：Provider 动态注册表（SQLite）。YAML 仅首次播种数据库中缺失且有效的条目；之后数据库为唯一事实源。 */
  @Bean
  ProviderRegistry providerRegistry(
      LlmProviderRepository repository,
      ProvidersProperties properties,
      ProviderRegistryBootstrap bootstrap,
      io.oryxos.core.secret.SecretCipher secretCipher,
      io.oryxos.storage.SecretMigration secretMigration) {
    // 022：注册表收口加解密；依赖 SecretMigration 保证「守卫+存量迁移」先于 YAML 播种与一切读写
    ProviderRegistry registry = new JpaProviderRegistry(repository, secretCipher);
    bootstrap.seedMissing(registry, properties);
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
  PricingStore pricingStore(LlmPricingRepository repository) {
    return new JpaPricingStore(repository);
  }

  /** 022：落库凭证加解密——主密钥两档解析（ORYXOS_MASTER_KEY 优先，缺省 {oryxos.root}/master.key 首启自动生成）。 */
  @Bean
  io.oryxos.core.secret.SecretCipher secretCipher() {
    return new io.oryxos.core.secret.LocalMasterKeyCipher(
        new io.oryxos.core.secret.MasterKeyResolver(oryxosRoot()).resolve());
  }

  /** 022：存量明文迁移 + 密钥守卫（幂等；密钥不匹配拒启指路）。025 起表结构由 Flyway 收敛，迁移完才跑。 */
  @Bean
  @DependsOn("flywayInitializer")
  io.oryxos.storage.SecretMigration secretMigration(
      LlmProviderRepository providerRepository,
      NotifyChannelRepository channelRepository,
      io.oryxos.core.secret.SecretCipher secretCipher) {
    io.oryxos.storage.SecretMigration migration =
        new io.oryxos.storage.SecretMigration(providerRepository, channelRepository, secretCipher);
    migration.run();
    return migration;
  }

  /** 023：业务指标——serve 场景（actuator 在类路径且有 MeterRegistry）落 Micrometer；chat 等无监控上下文 NOOP 兜底。 */
  @Bean
  io.oryxos.core.metrics.MetricsRecorder metricsRecorder(
      org.springframework.beans.factory.ObjectProvider<io.micrometer.core.instrument.MeterRegistry>
          meterRegistry) {
    io.micrometer.core.instrument.MeterRegistry registry = meterRegistry.getIfAvailable();
    return registry == null
        ? io.oryxos.core.metrics.MetricsRecorder.NOOP
        : new MicrometerMetricsRecorder(registry);
  }

  @Bean
  ProviderService providerService(
      ProviderRegistry providerRegistry,
      LlmCallAuditor auditor,
      PricingStore pricingStore,
      io.oryxos.core.metrics.MetricsRecorder metricsRecorder) {
    // 动态解析（31 节）：按名从注册表取参数、经工厂即时建/缓存 ChatModel（宪法 III 显式映射，只是运行时可变）
    ProviderChatModelFactory factory = new ProviderChatModelFactory();
    return new SpringAiProviderServiceImpl(
        providerRegistry,
        def -> factory.buildOne(def.name(), def.apiKey(), def.baseUrl()),
        new ToolSchemaAdapter(),
        auditor,
        pricingStore,
        metricsRecorder); // 023：LLM 调用/token/切换指标
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
  ProfileRegistry profileRegistry(
      AgentLoader agentLoader, AgentSkillStartupReport ignoredSkillStartupReport) {
    // 启动全量扫；30 节 WorkspaceWatcher 负责启动后的实时变更（同一段 register）
    return agentLoader.loadAll();
  }

  @Bean
  AgentStore agentStore() {
    return new AgentStore(oryxosRoot());
  }

  /** 025 人格库：默认人格预设目录（12 个 agency-agents-zh 专家源文件随 jar 内置，Web/CLI 导入的预置内容种子）。 */
  @Bean
  PersonaPresetCatalog personaPresetCatalog() {
    return new PersonaPresetCatalog();
  }

  /** 025 人格库：自定义人格的工作区 store（{@code .oryxos/personas/} 扁平 .md，只放用户自建，不播种内置）。 */
  @Bean
  PersonaStore personaStore() {
    return new PersonaStore(oryxosRoot());
  }

  /** 025 人格库：只读内置 + 可 CRUD 自定义的统一编排（copy-in 模板库，仍非按名引用的人格市场）。 */
  @Bean
  PersonaService personaService(
      PersonaPresetCatalog personaPresetCatalog, PersonaStore personaStore) {
    return new PersonaService(personaPresetCatalog, personaStore);
  }

  /** 30 节：Agent 生命周期编排。创建脚手架的 AGENT.md 模板里 provider 缺省取最终注册表按名称大小写不敏感的最小项。 */
  @Bean
  AgentLifecycleService agentLifecycleService(
      AgentLoader agentLoader,
      ProfileRegistry profileRegistry,
      AgentScheduler agentScheduler,
      AgentStore agentStore,
      ProviderService providerService,
      ProviderRegistry providerRegistry,
      Map<String, OryxTool> tools,
      NotifyChannelRegistry notifyChannelRegistry,
      io.oryxos.core.mcp.McpServerAdmin mcpServerAdmin,
      SkillRegistry skillRegistry,
      AgentSkillBindingService skillBindings,
      SkillCatalog skillCatalog,
      io.oryxos.core.knowledge.KnowledgeService knowledgeService,
      @Value("${oryxos.author.provider:}") String authorProvider,
      @Value("${oryxos.author.model:}") String authorModel) {
    String defaultProvider =
        providerRegistry.list().stream()
            .map(ProviderDef::name)
            .filter(name -> name != null && !name.isBlank())
            // 大小写不敏感取最小项：ASCII 排序会先排大写，让「大写开头的本地 provider（如 Qwen3.8-27B-gptq-w4a16）」
            // 顶掉真正的默认 deepseek——这里统一按大小写无关的最小名取确定性默认。
            .min(String::compareToIgnoreCase)
            .orElse(null);
    // 生成用 provider 缺省取最终注册表的确定性默认；显式 oryxos.author.provider 仍按原行为覆盖。
    String genProvider =
        authorProvider == null || authorProvider.isBlank() ? defaultProvider : authorProvider;
    // 30 节：把真实工具清单 + notify 渠道注入作者提示词，让"一句话生成"只用真实能力、可直接运行
    // 31 节：再把已连接 MCP server 目录也喂给它，生成的 AGENT.md 才可能正确带上 mcp_servers
    // 014：再把已有知识库名单（name → description）也喂给它，生成的草稿才可能带出正确的绑定建议（FR-018）
    return new AgentLifecycleService(
        agentLoader,
        profileRegistry,
        agentScheduler,
        agentStore,
        providerService,
        defaultProvider,
        genProvider,
        authorModel,
        tools,
        notifyChannelRegistry,
        mcpServerAdmin,
        skillRegistry,
        skillBindings,
        skillCatalog,
        () ->
            knowledgeService.listBases().stream()
                .collect(
                    java.util.stream.Collectors.toMap(
                        io.oryxos.core.knowledge.model.KnowledgeBaseInfo::name,
                        io.oryxos.core.knowledge.model.KnowledgeBaseInfo::description,
                        (a, b) -> a,
                        java.util.LinkedHashMap::new)));
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

  /**
   * #332：ContextClosedEvent 先于 SmartLifecycle 停机发布（AbstractApplicationContext.doClose 顺序）， 在此关闭两个
   * WatchService → 监听循环退出 → 执行器「运行中任务数」归零 → 其 stop 回调即时触发， 生命周期停机不再等满 30s latch 超时。不关的话
   * watchService.take() 无事件/中断/关闭三者不醒。
   */
  @Bean
  ApplicationListener<ContextClosedEvent> watcherGracefulShutdown(
      WorkspaceWatcher workspaceWatcher,
      io.oryxos.knowledge.watch.KnowledgeWatcher knowledgeWatcher) {
    return event -> {
      workspaceWatcher.stop();
      knowledgeWatcher.stop();
    };
  }

  /** 30 节：实时监听 .oryxos/agents/——守护线程上跑监听循环，启动后的变更走同一段 register。 */
  @Bean(initMethod = "start")
  WorkspaceWatcher workspaceWatcher(
      AgentLifecycleService agentLifecycleService,
      ThreadPoolTaskExecutor workspaceWatcherExecutor) {
    return new WorkspaceWatcher(agentLifecycleService, oryxosRoot(), workspaceWatcherExecutor);
  }

  @Bean
  ContextLoader contextLoader(
      AgentSkillBindingService skillBindings,
      io.oryxos.core.knowledge.KnowledgeBindingService knowledgeBindings) {
    return new ContextLoader(oryxosRoot(), skillBindings, knowledgeBindings);
  }

  // ---- 014 知识库：契约在 core、实现经 oryxos-knowledge、按名注册（宪法 III 同款哲学）----

  @Bean
  io.oryxos.core.knowledge.KnowledgeBindingService knowledgeBindingService() {
    return new io.oryxos.core.knowledge.KnowledgeBindingService(oryxosRoot());
  }

  /** 向量索引存储可插拔位（research D1）：默认 sqlite；未知档位明确报错不静默降级。 */
  @Bean
  io.oryxos.knowledge.store.ChunkStore chunkStore(
      @Value("${knowledge.store:sqlite}") String store,
      io.oryxos.storage.KnowledgeDocumentRepository documentRepository,
      io.oryxos.storage.KnowledgeChunkRepository chunkRepository) {
    return switch (store) {
      case "sqlite" ->
          new io.oryxos.knowledge.store.SqliteChunkStore(documentRepository, chunkRepository);
      case "memory" -> new io.oryxos.knowledge.store.InMemoryChunkStore(); // 测试/演示档，重启即失
      default ->
          throw new IllegalStateException("未知的 knowledge.store: " + store + "（支持 sqlite / memory）");
    };
  }

  /**
   * embedding 供给者（015 起全局共享：知识与记忆同用一套向量化配置）：按名从 Provider 注册表取凭证即时构造并缓存。 配置键 {@code
   * embedding.provider/model}，为空时回读旧键 {@code knowledge.embedding.*}（FR-015 兼容别名，
   * 存量部署零改动）。未配置不静默回退——lazy 抛可读异常，由检索降级/导入报错消化（FR-013）。
   */
  @Bean
  java.util.function.Supplier<io.oryxos.core.embedding.TextEmbedder> textEmbedderSupplier(
      ProviderRegistry providerRegistry,
      @Value("${embedding.provider:${knowledge.embedding.provider:}}") String embeddingProvider,
      @Value("${embedding.model:${knowledge.embedding.model:}}") String embeddingModel) {
    io.oryxos.provider.ProviderEmbeddingModelFactory factory =
        new io.oryxos.provider.ProviderEmbeddingModelFactory();
    java.util.concurrent.atomic.AtomicReference<io.oryxos.core.embedding.TextEmbedder> cache =
        new java.util.concurrent.atomic.AtomicReference<>();
    return () -> {
      io.oryxos.core.embedding.TextEmbedder cached = cache.get();
      if (cached != null) {
        return cached;
      }
      if (embeddingProvider == null || embeddingProvider.isBlank()) {
        throw new IllegalArgumentException(
            "未配置 embedding provider（embedding.provider，兼容旧键 knowledge.embedding.provider）："
                + "向量化不可用，请配置支持 embedding 端点的 provider（如 qwen/zhipu）或 mock");
      }
      ProviderDef def =
          providerRegistry
              .find(embeddingProvider)
              .orElseThrow(
                  () ->
                      new IllegalArgumentException(
                          "embedding provider 不存在于 Provider 注册表: " + embeddingProvider));
      io.oryxos.core.embedding.TextEmbedder built =
          factory.buildOne(def.name(), def.apiKey(), def.baseUrl(), embeddingModel);
      cache.set(built);
      return built;
    };
  }

  @Bean
  io.oryxos.knowledge.index.KnowledgeIndexService knowledgeIndexService(
      io.oryxos.knowledge.store.ChunkStore chunkStore,
      java.util.function.Supplier<io.oryxos.core.embedding.TextEmbedder> textEmbedderSupplier,
      ExecutorService agentExecutionExecutor) {
    // 两段式导入的后台段跑在虚拟线程执行器上（宪法 VII：同步代码 + 虚拟线程，无异步编程模型）
    return new io.oryxos.knowledge.index.KnowledgeIndexService(
        oryxosRoot().resolve("knowledge"),
        chunkStore,
        textEmbedderSupplier,
        agentExecutionExecutor);
  }

  @Bean
  io.oryxos.knowledge.LocalKnowledgeBackend localKnowledgeBackend(
      io.oryxos.knowledge.store.ChunkStore chunkStore,
      io.oryxos.knowledge.index.KnowledgeIndexService knowledgeIndexService,
      java.util.function.Supplier<io.oryxos.core.embedding.TextEmbedder> textEmbedderSupplier) {
    return new io.oryxos.knowledge.LocalKnowledgeBackend(
        oryxosRoot().resolve("knowledge"), chunkStore, knowledgeIndexService, textEmbedderSupplier);
  }

  @Bean
  io.oryxos.core.knowledge.KnowledgeBackendRegistry knowledgeBackendRegistry(
      io.oryxos.knowledge.LocalKnowledgeBackend localKnowledgeBackend) {
    io.oryxos.core.knowledge.KnowledgeBackendRegistry registry =
        new io.oryxos.core.knowledge.KnowledgeBackendRegistry();
    registry.register(localKnowledgeBackend);
    return registry;
  }

  /** 知识库热加载专用守护线程执行器（与 WorkspaceWatcher 同款形态）。 */
  @Bean
  ThreadPoolTaskExecutor knowledgeWatcherExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(1);
    executor.setMaxPoolSize(1);
    executor.setThreadNamePrefix("oryxos-knowledge-watcher-");
    executor.setDaemon(true);
    executor.initialize();
    return executor;
  }

  /** FR-010：实时监听 .oryxos/knowledge/——启动全量对账 + 运行中增改删收敛到索引（US4）。 */
  @Bean(initMethod = "start")
  io.oryxos.knowledge.watch.KnowledgeWatcher knowledgeWatcher(
      io.oryxos.knowledge.index.KnowledgeIndexService knowledgeIndexService,
      ThreadPoolTaskExecutor knowledgeWatcherExecutor) {
    return new io.oryxos.knowledge.watch.KnowledgeWatcher(
        oryxosRoot(), knowledgeIndexService, knowledgeWatcherExecutor);
  }

  @Bean
  io.oryxos.core.knowledge.KnowledgeService knowledgeService(
      io.oryxos.core.knowledge.KnowledgeBindingService knowledgeBindingService,
      io.oryxos.core.knowledge.KnowledgeBackendRegistry knowledgeBackendRegistry) {
    return new io.oryxos.core.knowledge.KnowledgeServiceImpl(
        oryxosRoot().resolve("knowledge"), knowledgeBindingService, knowledgeBackendRegistry);
  }

  @Bean
  SkillStore skillStore() {
    return new SkillStore(oryxosRoot());
  }

  @Bean
  SkillLoader skillLoader() {
    return new SkillLoader(oryxosRoot().resolve("skills"));
  }

  /** 32 节：启动全量扫 .oryxos/skills/ 建全局 Skill 索引（CRUD 与它共用同一份注册表）。 */
  @Bean
  SkillRegistry skillRegistry(SkillLoader skillLoader) {
    return skillLoader.loadAll();
  }

  @Bean
  AgentSkillBindingService agentSkillBindingService(SkillLoader skillLoader) {
    return new AgentSkillBindingService(oryxosRoot(), skillLoader);
  }

  @Bean
  SkillCatalog skillCatalog(SkillRegistry skillRegistry) {
    return new InstalledSkillCatalog(skillRegistry);
  }

  /** 32 节：全局 Skill 库 CRUD；启动播种内置 Skill（report-format，幂等——用户改过不覆盖）。 */
  @Bean
  SkillService skillService(
      SkillStore skillStore,
      SkillRegistry skillRegistry,
      SkillLoader skillLoader,
      AgentSkillBindingService skillBindings) {
    SkillService service = new SkillService(skillStore, skillRegistry, skillLoader, skillBindings);
    service.seedBuiltins();
    return service;
  }

  @Bean
  AgentSkillStartupReport agentSkillStartupReport(
      SkillService ignoredSeededSkills, AgentSkillBindingService skillBindings) {
    return new AgentSkillMigrationService(oryxosRoot(), skillBindings).migrateAll();
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
      HttpSandboxProperties httpProps,
      SmtpSandboxProperties smtpProps) {
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
    nullToEmpty(smtpProps.allowedEndpoints()).forEach(e -> whitelist.add(Category.SMTP, e));
    // 工作区根永远是 Agent 的家：随 oryxos.root 自动纳入文件白名单（幂等 + 落库）。
    whitelist.add(Category.FILE, oryxosRootProp);
    return whitelist;
  }

  private static List<String> nullToEmpty(List<String> list) {
    return list == null ? List.of() : list;
  }

  /** 工具 HTTP 连接超时（秒）的系统属性名：默认 10，{@code -Doryxos.tool.http.connect-timeout-seconds=N} 覆盖。 */
  static final String TOOL_HTTP_CONNECT_TIMEOUT_PROP = "oryxos.tool.http.connect-timeout-seconds";

  /** 工具 HTTP 读取超时（秒）的系统属性名：默认 30，{@code -Doryxos.tool.http.read-timeout-seconds=N} 覆盖。 */
  static final String TOOL_HTTP_READ_TIMEOUT_PROP = "oryxos.tool.http.read-timeout-seconds";

  private static final long DEFAULT_TOOL_HTTP_CONNECT_TIMEOUT_SECONDS = 10;
  private static final long DEFAULT_TOOL_HTTP_READ_TIMEOUT_SECONDS = 30;

  /**
   * 工具 HTTP 请求工厂：连接/读取超时可经系统属性覆盖，防止远端挂死时永久阻塞 Agent 调用。
   *
   * <p>提取为 static 方法便于单测直接验证超时行为（无需启 Spring 容器），模式同 {@code
   * ProviderChatModelFactory.timeoutFactory()}。
   *
   * <p>同时 {@code followRedirects(NEVER)}：默认 NORMAL 会跟随 302；Mem0 等客户端会附带 {@code
   * Authorization}/{@code X-API-Key}，恶意或被劫持的 {@code memory.mem0.base-url} 可把请求拐到内网/元数据。
   */
  static JdkClientHttpRequestFactory toolHttpRequestFactory() {
    Duration connectTimeout =
        Duration.ofSeconds(
            Long.getLong(
                TOOL_HTTP_CONNECT_TIMEOUT_PROP, DEFAULT_TOOL_HTTP_CONNECT_TIMEOUT_SECONDS));
    Duration readTimeout =
        Duration.ofSeconds(
            Long.getLong(TOOL_HTTP_READ_TIMEOUT_PROP, DEFAULT_TOOL_HTTP_READ_TIMEOUT_SECONDS));
    JdkClientHttpRequestFactory factory =
        new JdkClientHttpRequestFactory(
            HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build());
    factory.setReadTimeout(readTimeout);
    return factory;
  }

  @Bean
  RestClient restClient() {
    return RestClient.builder().requestFactory(toolHttpRequestFactory()).build();
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
          String mem0UserId,
      @org.springframework.beans.factory.annotation.Value("${memory.mem0.api-key:}")
          String mem0ApiKey) {
    return switch (backend) {
      case "sqlite" -> new SqliteMemoryStore(memoryEntryRepository);
      case "mem0" ->
          new Mem0MemoryStore(
              restClient.mutate().baseUrl(mem0BaseUrl).build(), mem0UserId, mem0ApiKey);
      default -> new MarkdownMemoryStore(oryxosRoot());
    };
  }

  /**
   * 015 检索装配（FR-001/013）：embedding 未配置 = 纯关键词旧行为（SC-002 字节级兼容）；已配置 = 三路加权引擎 + 有界异步向量索引（延迟解析
   * embedder——配置错误在调用点转可读降级，不阻断启动）。
   */
  @Bean
  MemoryServiceImpl memoryService(
      LongTermMemoryStore store,
      io.oryxos.storage.MemoryVectorRepository memoryVectorRepository,
      java.util.function.Supplier<io.oryxos.core.embedding.TextEmbedder> textEmbedderSupplier,
      @Value("${embedding.provider:${knowledge.embedding.provider:}}") String embeddingProvider,
      @Value("${memory.recall.weight.semantic:1.0}") double semanticWeight,
      @Value("${memory.recall.weight.keyword:1.0}") double keywordWeight,
      @Value("${memory.recall.weight.recency:1.0}") double recencyWeight,
      @Value("${memory.recall.top-k:20}") int recallTopK) {
    if (embeddingProvider == null || embeddingProvider.isBlank()) {
      return new MemoryServiceImpl(store);
    }
    io.oryxos.core.embedding.TextEmbedder embedder =
        new io.oryxos.memory.DeferredTextEmbedder(textEmbedderSupplier);
    double[] weights = {semanticWeight, keywordWeight, recencyWeight};
    return new MemoryServiceImpl(
        store,
        new io.oryxos.memory.MemoryRecallEngine(
            memoryVectorRepository, embedder, weights, recallTopK),
        io.oryxos.memory.MemoryVectorIndex.withBoundedExecutor(memoryVectorRepository, embedder));
  }

  /** 015 FR-007/SC-006：启动对账——每个已知 Agent + 全局作用域各一次；失败仅告警（索引随写入或下次启动追齐）。 */
  @Bean
  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "CRLF_INJECTION_LOGS",
      justification = "日志中的作用域名与异常消息已经 sanitizeLog() 消去 CR/LF；taint 分析不跨方法追踪该消毒，故局部抑制")
  org.springframework.boot.ApplicationRunner memoryIndexReconciler(
      MemoryServiceImpl memoryService, ProfileRegistry profileRegistry) {
    return args -> {
      java.util.LinkedHashSet<String> scopes = new java.util.LinkedHashSet<>();
      profileRegistry.all().forEach(profile -> scopes.add(profile.name()));
      scopes.add("__global__");
      for (String scope : scopes) {
        try {
          memoryService.reconcileIndex(scope);
        } catch (RuntimeException e) {
          org.slf4j.LoggerFactory.getLogger(OryxOsRuntime.class)
              .warn(
                  "记忆索引启动对账失败（作用域 {}，随写入/下次启动追齐）: {}",
                  sanitizeLog(scope),
                  sanitizeLog(e.getMessage()));
        }
      }
    };
  }

  /** 日志参数消毒：去掉换行，防日志伪造（CRLF injection）。 */
  private static String sanitizeLog(String value) {
    return value == null ? "" : value.replace('\r', '_').replace('\n', '_');
  }

  /** 31 节：MCP server 配置读写（读写 {@code .oryxos/mcp_servers.yaml}），管理台 CRUD 与启动扫描共用同一份。 */
  @Bean
  McpConfigLoader mcpConfigLoader() {
    return new McpConfigLoader(oryxosRoot().resolve("mcp_servers.yaml"));
  }

  /** 31 节：长驻 bean（而非 toolRegistry() 里的一次性局部变量）——管理台增/删一个 server 靠同一个实例的连接状态。 */
  @Bean
  McpClientService mcpClientService(McpConfigLoader mcpConfigLoader) {
    return new McpClientService(mcpConfigLoader);
  }

  /**
   * chat（{@code WebApplicationType.NONE}）读控制台；serve/gateway（Servlet web）无人值守，ask_user 必须立刻报不支持，
   * 绝不能堵在服务端 {@code System.in} 上。
   */
  @Bean
  UserInteraction userInteraction(ApplicationContext applicationContext) {
    return resolveUserInteraction(applicationContext instanceof WebApplicationContext);
  }

  /** 可见给单测：Servlet web → 不支持交互；否则 → 控制台。 */
  static UserInteraction resolveUserInteraction(boolean servletWeb) {
    if (servletWeb) {
      return new UnsupportedUserInteraction();
    }
    return new ConsoleUserInteraction();
  }

  @Bean
  ToolRegistry toolRegistry(
      Sandbox sandbox,
      RestClient restClient,
      MemoryService memoryService,
      NotifyChannelRegistry notifyChannelRegistry,
      McpClientService mcpClientService,
      UserInteraction userInteraction,
      io.oryxos.core.knowledge.KnowledgeService knowledgeService,
      ExecutionBackendProperties executionBackendProperties) {
    ToolRegistry registry = new ToolRegistry();
    // 内置工具走 @Tool 注解管道（schema 自动生成，宪法 II 第二件事）
    registry.registerAnnotated(new FileTools(sandbox)); // read/write/list/edit/grep/glob
    // 024：执行后端按档位装配（local=现状零变化 / docker=短命容器），白名单 enforce 仍在工具内部前置（FR-007）
    ProcessStarter shellStarter =
        executionBackendProperties.isDocker()
            ? new DockerProcessStarter(
                executionBackendProperties,
                new WorkspacePathMapper(oryxosRoot()),
                CidfileProcessWrapper.dockerCliKiller())
            : new LocalProcessStarter();
    registry.registerAnnotated(new ShellTools(sandbox, shellStarter));
    registry.registerAnnotated(
        new HttpTools(sandbox, restClient)); // + http_request/fetch_webpage/download_file
    registry.registerAnnotated(new UtilTools()); // current_time / json_extract（纯计算，无沙箱）
    registry.registerAnnotated(
        new WebSearchTools(sandbox, new DuckDuckGoSearchProvider(restClient, sandbox)));
    registry.registerAnnotated(
        new FormatTools(sandbox)); // format_sql / export_excel（写路径过 FILE 白名单）
    // chat → ConsoleUserInteraction；serve/gateway → UnsupportedUserInteraction（见 userInteraction
    // bean）
    registry.registerAnnotated(new InteractionTools(userInteraction));
    // notify（19 节 OryxTool 形态）直接注册——渠道实现按 channelType 路由；出网经 NotifyPoster 逐跳复检白名单
    NotifyPoster notifyPoster = new NotifyPoster(sandbox);
    Map<String, NotifyChannelAdapter> notifyAdapters =
        Map.of(
            "webhook", new WebhookNotifyAdapter(notifyPoster),
            "wecom", new WeComNotifyAdapter(notifyPoster),
            "feishu", new FeishuNotifyAdapter(notifyPoster),
            "dingtalk", new DingTalkNotifyAdapter(notifyPoster),
            "email", new EmailNotifyAdapter(sandbox));
    registry.register(new NotifyTools(notifyAdapters, sandbox, notifyChannelRegistry));
    // 记忆工具：save_memory / recall_memory（补齐 20 节预留的两工具面），只认门面对后端无感
    registry.registerAnnotated(new MemoryTools(memoryService));
    // 知识检索工具（014）：retrieve_knowledge——只认门面，范围由门面按发起 Agent 的绑定圈定
    registry.registerAnnotated(
        new io.oryxos.knowledge.builtin.KnowledgeTools(
            knowledgeService, oryxosRoot().resolve("knowledge")));
    // MCP：失联的 server 只 WARN 跳过，不拖垮启动；31 节起走长驻 bean，管理台 CRUD 复用同一份连接状态
    mcpClientService.connectAll(registry);
    return registry;
  }

  /** 31 节：MCP server 管理台 CRUD 落地实现——core 契约 {@code McpServerAdmin}，web 层只认接口不认这个类。 */
  @Bean
  io.oryxos.core.mcp.McpServerAdmin mcpServerAdmin(
      McpConfigLoader mcpConfigLoader,
      McpClientService mcpClientService,
      ToolRegistry toolRegistry) {
    return new io.oryxos.tool.mcp.McpServerAdminService(
        mcpConfigLoader, mcpClientService, toolRegistry);
  }

  @Bean
  Map<String, OryxTool> tools(ToolRegistry toolRegistry) {
    // 20 节起：全部来源统一经 ToolRegistry 供给（PromptBuilder 按 Profile.tools 过滤）。
    // 活视图：管理台 MCP CRUD「立即生效」必须打到同一份 Map，不能 copyOf 冻在启动瞬间。
    return toolRegistry.asMap();
  }

  /**
   * 020-tool-policy：工具策略（平台治理层）。ownerLookup 走 ToolRegistry 活视图——MCP server 增删后 {@code server:*}
   * 通配即刻按新归属判定。
   */
  @Bean
  io.oryxos.core.policy.ToolPolicyService toolPolicyService(
      io.oryxos.storage.ToolPolicyRuleRepository repository, ToolRegistry toolRegistry) {
    return new io.oryxos.storage.ToolPolicyServiceImpl(
        repository, name -> toolRegistry.mcpToolOwners().get(name));
  }

  /**
   * 020：策略加载期告警（未知目标规则 / 有效集全空，WARN 不阻断）。仅 SERVLET 模式（serve/gateway）跑—— CLI 管理命令用
   * WebApplicationType.NONE，不受影响（镜像 018 ApiKeyStartupCheck 的条件口径）。
   */
  @Bean
  @org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication(
      type =
          org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type.SERVLET)
  ToolPolicyStartupCheck toolPolicyStartupCheck(
      io.oryxos.core.policy.ToolPolicyService toolPolicyService,
      ProfileRegistry profileRegistry,
      ToolRegistry toolRegistry) {
    return new ToolPolicyStartupCheck(toolPolicyService, profileRegistry, toolRegistry);
  }

  /** 024 FR-005：docker 档启动校验（CLI/daemon/镜像，fail loud）；local 档零检查零开销。 */
  @Bean
  @org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication(
      type =
          org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type.SERVLET)
  DockerBackendStartupCheck dockerBackendStartupCheck(ExecutionBackendProperties props) {
    return new DockerBackendStartupCheck(props);
  }

  @Bean
  PromptBuilder promptBuilder(
      ContextLoader contextLoader,
      Map<String, OryxTool> tools,
      MemoryService memoryService,
      io.oryxos.core.policy.ToolPolicyService toolPolicyService) {
    // 22 节起：注入 MemoryService，长期记忆段由门面供给（会话历史段仍由 PromptBuilder 独立负责）
    PromptBuilder builder =
        new PromptBuilder(contextLoader, tools, memoryService, java.time.Clock.systemDefaultZone());
    builder.setToolPolicy(toolPolicyService); // 020：事前过滤——被 deny 工具不进模型清单
    return builder;
  }

  @Bean
  ToolExecutor toolExecutor(
      Map<String, OryxTool> tools,
      ToolRegistry toolRegistry,
      ProfileRegistry profileRegistry,
      ToolInvocationAuditor auditor,
      io.oryxos.core.policy.ToolPolicyService toolPolicyService,
      io.oryxos.core.metrics.MetricsRecorder metricsRecorder) {
    // 31 节：mcp_servers 白名单在此接线。mcpToolOwners() 是活视图，与 tools bean 一样不能在构造时 copyOf。
    ToolExecutor executor =
        new ToolExecutor(tools, toolRegistry.mcpToolOwners(), profileRegistry, auditor);
    executor.setToolPolicy(toolPolicyService); // 020：事中裁决——防幻觉调用与热更新窗口
    executor.setMetricsRecorder(metricsRecorder); // 023：工具调用/策略拦截指标
    return executor;
  }

  @Bean
  InterruptManager interruptManager() {
    return new InterruptManager();
  }

  @Bean
  ReActLoop reActLoop(
      PromptBuilder promptBuilder,
      ProviderService providerService,
      ToolExecutor toolExecutor,
      InterruptManager interruptManager) {
    return new ReActLoop(promptBuilder, providerService, toolExecutor, interruptManager);
  }

  @Bean
  SessionManager sessionManager(SessionRepository repository) {
    return new JpaSessionManager(repository);
  }

  /** 012-web-auth：管理台 Basic Auth 账号管理（密码哈希由 PasswordEncoderFactory 的 bean 提供）。 */
  @Bean
  WebUserService webUserService(WebUserRepository repository, PasswordEncoder passwordEncoder) {
    return new WebUserService(repository, passwordEncoder);
  }

  /**
   * 012-web-auth US3：浏览器登录 session 管理（create/findValid 惰性清过期/delete）。ttl 走 @Value 读字面量，避免 cli 引
   * oryxos-web 的 WebAuthProperties 类。
   */
  @Bean
  WebSessionService webSessionService(
      WebSessionRepository repository,
      @org.springframework.beans.factory.annotation.Value("${oryxos.web.auth.session-ttl:12h}")
          java.time.Duration sessionTtl) {
    return new WebSessionService(repository, sessionTtl);
  }

  /** 018-rest-api-key：REST API Key 生成/校验/吊销（只存 SHA-256 哈希，明文仅生成时返回一次）。 */
  @Bean
  ApiKeyService apiKeyService(ApiKeyRepository repository) {
    return new ApiKeyService(repository);
  }

  @Bean
  NotifyChannelRegistry notifyChannelRegistry(
      NotifyChannelRepository repository,
      io.oryxos.core.secret.SecretCipher secretCipher,
      io.oryxos.storage.SecretMigration secretMigration) {
    // 022：同 providerRegistry——收口加解密，且迁移先行
    return new JpaNotifyChannelRegistry(repository, secretCipher);
  }

  @Bean
  AgentService agentService(
      ProfileRegistry profileRegistry, ReActLoop reActLoop, SessionManager sessionManager) {
    return new AgentService(profileRegistry, reActLoop, sessionManager);
  }

  @Bean
  CliChannel cliChannel(AgentService agentService, SessionManager sessionManager) {
    return new CliChannel(agentService, sessionManager);
  }

  // ── 017：入站 IM 渠道（飞书长连接）────────────────────────────────────────

  @Bean
  io.oryxos.core.channel.ChannelConfigLoader channelConfigLoader() {
    return new io.oryxos.core.channel.ChannelConfigLoader(oryxosRoot().resolve("channels.yaml"));
  }

  @Bean
  io.oryxos.core.channel.MessageDeduplicator messageDeduplicator() {
    return new io.oryxos.core.channel.MessageDeduplicator();
  }

  @Bean
  io.oryxos.core.channel.InboundChannelRegistry inboundChannelRegistry() {
    return new io.oryxos.core.channel.InboundChannelRegistry();
  }

  @Bean
  io.oryxos.core.channel.InboundMessageService inboundMessageService(
      AgentService agentService,
      SessionManager sessionManager,
      ProfileRegistry profileRegistry,
      AgentExecutionService agentExecutionService,
      io.oryxos.core.channel.MessageDeduplicator messageDeduplicator,
      InterruptManager interruptManager,
      io.oryxos.core.metrics.MetricsRecorder metricsRecorder) {
    return new io.oryxos.core.channel.InboundMessageService(
        agentService,
        sessionManager,
        profileRegistry,
        agentExecutionService,
        messageDeduplicator,
        new io.oryxos.core.channel.DefaultInboundMediaEnricher(
            io.oryxos.cli.WhisperHttpTranscriber.fromEnv(), metricsRecorder),
        java.time.Duration.ofSeconds(15), // 「处理中」提示延迟（Edge Case：先行告知）
        interruptManager);
  }

  /** 渠道出站守卫：渠道自建 HTTP 不被沙箱自动拦截，经此显式复用 http 域名白名单（宪法 VI / 017 R7）。 */
  @Bean
  io.oryxos.core.channel.OutboundGuard channelOutboundGuard(WhitelistSandbox sandbox) {
    return url ->
        sandbox.enforce(
            new io.oryxos.tool.sandbox.SandboxAction(
                io.oryxos.tool.sandbox.ActionType.HTTP_REQUEST, url));
  }

  /**
   * 渠道管理：落盘 + 断旧建新即生效（无需重启，复刻 MCP admin 模式）。initMethod=startAll 启动恢复全部渠道， 单条失败登记 ERROR
   * 点名原因不阻断启动；destroyMethod=stopAll 关闭时断开长连接。
   */
  @Bean(initMethod = "startAll", destroyMethod = "stopAll")
  io.oryxos.core.channel.ChannelAdminService channelAdminService(
      io.oryxos.core.channel.ChannelConfigLoader channelConfigLoader,
      io.oryxos.core.channel.InboundChannelRegistry inboundChannelRegistry,
      ProfileRegistry profileRegistry,
      io.oryxos.core.channel.InboundMessageService inboundMessageService,
      io.oryxos.core.channel.OutboundGuard channelOutboundGuard) {
    return new io.oryxos.core.channel.ChannelAdminService(
        channelConfigLoader,
        inboundChannelRegistry,
        profileRegistry,
        Map.of(
            io.oryxos.channel.feishu.FeishuChannelAdapter.TYPE,
            resolved ->
                new io.oryxos.channel.feishu.FeishuChannelAdapter(
                    resolved, profileRegistry, inboundMessageService, channelOutboundGuard),
            io.oryxos.channel.wecom.WeComChannelAdapter.TYPE,
            resolved ->
                new io.oryxos.channel.wecom.WeComChannelAdapter(
                    resolved, profileRegistry, inboundMessageService, channelOutboundGuard),
            io.oryxos.channel.dingtalk.DingTalkChannelAdapter.TYPE,
            resolved ->
                new io.oryxos.channel.dingtalk.DingTalkChannelAdapter(
                    resolved, profileRegistry, inboundMessageService, channelOutboundGuard)));
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

  /** 28 节：定时任务状态与执行历史落库（重启不丢），并支撑管理台的查看/立即执行/启用停用。 */
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
      ScheduledTaskStore scheduledTaskStore,
      AgentExecutionStore agentExecutionStore) {
    return new AgentScheduler(
        taskScheduler,
        profileRegistry,
        agentService,
        sessionManager,
        scheduledTaskStore,
        agentExecutionStore);
  }

  /** 32 节：Agent 执行历史落 SQLite（手动触发 + 定时触发都记，起止时间 / 状态）。 */
  @Bean
  AgentExecutionStore agentExecutionStore(AgentExecutionRepository repository) {
    return new JpaAgentExecutionStore(repository);
  }

  /** 32 节：异步触发的后台执行器——虚拟线程（宪法 VII：虚拟线程处理并发，非 Reactor/WebFlux）。 */
  @Bean(destroyMethod = "shutdown")
  @SuppressWarnings("PMD.ThreadPoolCreationRule") // Spring 管理完整生命周期；Java 21 虚拟线程无池参数可配置。
  ExecutorService agentExecutionExecutor() {
    return Executors.newVirtualThreadPerTaskExecutor();
  }

  @Bean
  AgentExecutionService agentExecutionService(
      AgentExecutionStore agentExecutionStore, ExecutorService agentExecutionExecutor) {
    return new AgentExecutionService(
        agentExecutionStore, agentExecutionExecutor, Clock.systemDefaultZone());
  }
}
