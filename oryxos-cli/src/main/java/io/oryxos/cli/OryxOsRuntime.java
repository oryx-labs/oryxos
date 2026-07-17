package io.oryxos.cli;

import io.oryxos.channel.cli.CliChannel;
import io.oryxos.core.OryxTool;
import io.oryxos.core.agent.AgentScheduler;
import io.oryxos.core.agent.AgentService;
import io.oryxos.core.agent.PromptBuilder;
import io.oryxos.core.agent.ReActLoop;
import io.oryxos.core.agent.ToolExecutor;
import io.oryxos.core.agent.ToolInvocationAuditor;
import io.oryxos.core.context.ContextLoader;
import io.oryxos.core.memory.MemoryService;
import io.oryxos.core.profile.ProfileLoader;
import io.oryxos.core.profile.ProfileRegistry;
import io.oryxos.core.provider.LlmCallAuditor;
import io.oryxos.core.provider.ProviderService;
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
import io.oryxos.storage.JpaSessionManager;
import io.oryxos.storage.JpaToolInvocationAuditor;
import io.oryxos.storage.LlmCallRepository;
import io.oryxos.storage.MemoryEntryRepository;
import io.oryxos.storage.SessionRepository;
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
import java.util.Map;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
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

  // 工作区根目录默认 ./.oryxos；可用系统属性 oryxos.root 覆盖（集成测试指向临时工作区，默认行为不变）。
  private static final Path ORYXOS_ROOT = Path.of(System.getProperty("oryxos.root", ".oryxos"));

  @Bean
  Map<String, ChatModel> providerMap(ProvidersProperties properties) {
    properties.validate(); // 缺失/非法配置启动即点名报错，不静默失败
    return new ProviderChatModelFactory().build(properties);
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
  ProviderService providerService(Map<String, ChatModel> providerMap, LlmCallAuditor auditor) {
    return new SpringAiProviderServiceImpl(providerMap, new ToolSchemaAdapter(), auditor);
  }

  @Bean
  ProfileRegistry profileRegistry(Map<String, ChatModel> providerMap) {
    return new ProfileLoader(ORYXOS_ROOT.resolve("profiles"), providerMap.keySet()).loadAll();
  }

  @Bean
  ContextLoader contextLoader() {
    return new ContextLoader(ORYXOS_ROOT);
  }

  @Bean
  WhitelistSandbox sandbox(
      FileSandboxProperties fileProps,
      ShellSandboxProperties shellProps,
      HttpSandboxProperties httpProps) {
    // 24 节：真正的白名单校验（宪法 VI 第一档）。三块白名单来自 application.yml，空列表 = deny-all。
    // PermissiveSandbox 保留在 tool 模块留档（Demo 验证专用），生产装配不再引用。
    // 返回具体类型（而非 Sandbox 接口）：同一实例既是校验墙 Sandbox 又是可管理白名单 SandboxWhitelist，
    // 具体类型让 Spring 同时按两个接口装配（工具注 Sandbox，Web 管理端点注 SandboxWhitelist）。
    return new WhitelistSandbox(fileProps, shellProps, httpProps);
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
      default -> new MarkdownMemoryStore(ORYXOS_ROOT);
    };
  }

  @Bean
  MemoryService memoryService(LongTermMemoryStore store) {
    return new MemoryServiceImpl(store);
  }

  @Bean
  ToolRegistry toolRegistry(Sandbox sandbox, RestClient restClient, MemoryService memoryService) {
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
    registry.register(new NotifyTools(notifyAdapters, sandbox));
    // 记忆工具：save_memory / recall_memory（补齐 20 节预留的两工具面），只认门面对后端无感
    registry.registerAnnotated(new MemoryTools(memoryService));
    // MCP：失联的 server 只 WARN 跳过，不拖垮启动
    new McpClientService(new McpConfigLoader(ORYXOS_ROOT.resolve("mcp_servers.yaml")))
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
  AgentService agentService(
      ProfileRegistry profileRegistry, ReActLoop reActLoop, SessionManager sessionManager) {
    return new AgentService(profileRegistry, reActLoop, sessionManager);
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

  /** 第三触发源"钟推"（25 节）：initMethod=registerAll 启动即扫描所有 Profile.schedules 逐条注册。 */
  @Bean(initMethod = "registerAll")
  AgentScheduler agentScheduler(
      ThreadPoolTaskScheduler taskScheduler,
      ProfileRegistry profileRegistry,
      AgentService agentService,
      SessionManager sessionManager) {
    return new AgentScheduler(taskScheduler, profileRegistry, agentService, sessionManager);
  }
}
