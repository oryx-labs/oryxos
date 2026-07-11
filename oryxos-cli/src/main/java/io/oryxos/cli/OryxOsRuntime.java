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
import java.nio.file.Path;
import java.util.Map;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

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
  Map<String, OryxTool> tools() {
    // 内置工具与 MCP 工具 20 节经 ToolRegistry 接线；本节先以空表装配链路
    return Map.of();
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
