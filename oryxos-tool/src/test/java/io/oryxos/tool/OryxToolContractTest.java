package io.oryxos.tool;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import io.oryxos.core.OryxTool;
import io.oryxos.tool.builtin.FileTools;
import io.oryxos.tool.builtin.HttpTools;
import io.oryxos.tool.builtin.NotifyTools;
import io.oryxos.tool.builtin.ShellTools;
import io.oryxos.tool.notify.NotifyChannelAdapter;
import io.oryxos.tool.sandbox.PermissiveSandbox;
import io.oryxos.tool.sandbox.Sandbox;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.web.client.RestClient;

/**
 * 课件《第20节》验收 harness：OryxToolContractTest——参数化遍历注册表里每个工具， 任何一个工具漏实现契约三件套，这里立刻红（"动手前先检查"那条的自动化版）。
 */
class OryxToolContractTest {

  /** 与 OryxOsRuntime 装配同款的注册面：内置六个（注解管道）+ notify（直接注册）。 */
  static Stream<OryxTool> allRegisteredTools() {
    Sandbox sandbox = new PermissiveSandbox();
    ToolRegistry registry = new ToolRegistry();
    registry.registerAnnotated(new FileTools(sandbox));
    registry.registerAnnotated(new ShellTools(sandbox));
    registry.registerAnnotated(new HttpTools(sandbox, RestClient.create()));
    registry.registerAnnotated(
        new io.oryxos.tool.builtin.WebSearchTools(
            sandbox, new io.oryxos.tool.web.DuckDuckGoSearchProvider(RestClient.create())));
    registry.registerAnnotated(
        new io.oryxos.tool.builtin.InteractionTools(
            new io.oryxos.tool.interaction.UnsupportedUserInteraction()));
    registry.register(
        new NotifyTools(Map.of("webhook", mock(NotifyChannelAdapter.class)), sandbox));
    return registry.all().stream();
  }

  @ParameterizedTest
  @MethodSource("allRegisteredTools")
  @DisplayName("每个工具的契约三件套都不能缺")
  void everyRegisteredToolHasFullContract(OryxTool tool) {
    assertNotNull(tool.getName());
    assertNotNull(tool.getDescription());
    assertNotNull(tool.getInputSchema()); // 缺了它，Provider 翻译 Function Calling 时直接卡死
    assertFalse(tool.getName().isBlank());
    assertFalse(tool.getInputSchema().isBlank());
  }

  @ParameterizedTest
  @MethodSource("allRegisteredTools")
  @DisplayName("注解管道生成的 schema 含参数定义（管道有效性）")
  void annotatedPipelineSchemaContainsProperties(OryxTool tool) {
    // 全部内置工具都有入参：schema 必须是带 properties 的对象（自动生成有效性，US4）
    assertTrue(
        tool.getInputSchema().contains("properties") || "notify".equals(tool.getName()),
        tool.getName() + " 的 schema 应含参数定义: " + tool.getInputSchema());
  }
}
