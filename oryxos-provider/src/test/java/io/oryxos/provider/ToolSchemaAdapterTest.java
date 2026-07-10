package io.oryxos.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.oryxos.core.OryxTool;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.model.function.FunctionCallback;

/** 课件《第16节》验收 harness：ToolSchemaAdapterTest——只翻译、不执行。 */
class ToolSchemaAdapterTest {

  private final ToolSchemaAdapter adapter = new ToolSchemaAdapter();

  private OryxTool httpGetTool() {
    OryxTool tool = mock(OryxTool.class);
    when(tool.getName()).thenReturn("http_get");
    when(tool.getDescription()).thenReturn("发起一个 HTTP GET 请求");
    when(tool.getInputSchema())
        .thenReturn("{\"type\":\"object\",\"properties\":{\"url\":{\"type\":\"string\"}}}");
    return tool;
  }

  @Test
  void 翻译后_三要素与OryxTool一一对齐() {
    List<FunctionCallback> callbacks = adapter.toSpringAiTools(List.of(httpGetTool()));

    assertEquals(1, callbacks.size());
    FunctionCallback callback = callbacks.get(0);
    assertEquals("http_get", callback.getName());
    assertEquals("发起一个 HTTP GET 请求", callback.getDescription());
    assertTrue(callback.getInputTypeSchema().contains("\"url\""));
  }

  @Test
  void 翻译产物是纯描述_被调用执行时必须抛异常() {
    FunctionCallback callback = adapter.toSpringAiTools(List.of(httpGetTool())).get(0);

    // 第二道保险：即使有人绕过 proxyToolCalls，执行也走不通
    assertThrows(IllegalStateException.class, () -> callback.call("{\"url\":\"https://x\"}"));
  }

  @Test
  void 空工具列表_返回空() {
    assertTrue(adapter.toSpringAiTools(List.of()).isEmpty());
    assertTrue(adapter.toSpringAiTools(null).isEmpty());
  }
}
