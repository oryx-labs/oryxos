package io.oryxos.cli;

import io.oryxos.channel.cli.CliChannel;
import io.oryxos.core.OryxTool;
import io.oryxos.core.agent.AgentService;
import io.oryxos.core.agent.PromptBuilder;
import io.oryxos.core.agent.ReActLoop;
import io.oryxos.core.agent.ToolExecutor;
import io.oryxos.core.agent.ToolInvocationAuditor;
import io.oryxos.core.context.ContextLoader;
import io.oryxos.core.profile.ProfileLoader;
import io.oryxos.core.profile.ProfileRegistry;
import io.oryxos.core.provider.LlmCallAuditor;
import io.oryxos.core.provider.ProviderService;
import io.oryxos.core.session.SessionManager;
import io.oryxos.provider.ProviderChatModelFactory;
import io.oryxos.provider.ProvidersProperties;
import io.oryxos.provider.SpringAiProviderServiceImpl;
import io.oryxos.provider.ToolSchemaAdapter;
import io.oryxos.storage.JpaLlmCallAuditor;
import io.oryxos.storage.JpaSessionManager;
import io.oryxos.storage.JpaToolInvocationAuditor;
import io.oryxos.storage.LlmCallRepository;
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
import io.oryxos.tool.sandbox.PermissiveSandbox;
import io.oryxos.tool.sandbox.Sandbox;
import io.oryxos.tool.web.DuckDuckGoSearchProvider;
import java.nio.file.Path;
import java.util.Map;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
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
@EnableConfigurationProperties(ProvidersProperties.class)
public class OryxOsRuntime {

  private static final Path ORYXOS_ROOT = Path.of(".oryxos");

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
  Sandbox sandbox() {
    // 24 节替换为 WhitelistSandbox；Permissive 每次放行记 WARN，不静默裸奔
    return new PermissiveSandbox();
  }

  @Bean
  RestClient restClient() {
    return RestClient.create();
  }

  @Bean
  ToolRegistry toolRegistry(Sandbox sandbox, RestClient restClient) {
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
    registry.register(new NotifyTools(notifyAdapters));
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
  PromptBuilder promptBuilder(ContextLoader contextLoader, Map<String, OryxTool> tools) {
    return new PromptBuilder(contextLoader, tools);
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
}
