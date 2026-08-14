package io.oryxos.core.agent;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.oryxos.core.OryxTool;
import io.oryxos.core.ToolResult;
import io.oryxos.core.provider.ToolCallRequest;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 课件《第17节》验收 harness：ToolExecutorTest——成功要记、失败也要记。 */
class ToolExecutorTest {

  private OryxTool httpGet;
  private ToolInvocationAuditor auditor;
  private ToolExecutor executor;

  @BeforeEach
  void setUp() {
    httpGet = mock(OryxTool.class);
    when(httpGet.getName()).thenReturn("http_get");
    auditor = mock(ToolInvocationAuditor.class);
    executor = new ToolExecutor(Map.of("http_get", httpGet), auditor);
  }

  @Test
  @DisplayName("成功写审计 success=true")
  void successfulExecutionRecordsSuccessAudit() {
    when(httpGet.execute(any())).thenReturn(ToolResult.ok("晴，28°C"));

    ToolResult result =
        executor.execute(
            "s-1", "agent-x", new ToolCallRequest("http_get", "{\"url\":\"https://wttr.in\"}"));

    assertTrue(result.success());
    assertEquals("晴，28°C", result.content());
    verify(auditor)
        .record(
            eq("s-1"),
            eq("http_get"),
            contains("wttr.in"),
            contains("晴"),
            eq(true),
            isNull(),
            anyLong());
  }

  @Test
  @DisplayName("审计失败不上抛：结果照常返回、不重试工具或审计")
  void auditFailureDoesNotMaskToolResult() {
    when(httpGet.execute(any())).thenReturn(ToolResult.ok("ok"));
    doThrow(new IllegalStateException("audit unavailable"))
        .when(auditor)
        .record(eq("s-1"), eq("http_get"), anyString(), eq("ok"), eq(true), isNull(), anyLong());

    // 工具已执行完、副作用已发生：审计存储抖动不能让循环把这次执行当失败（否则模型可能重调有副作用的工具）
    ToolResult result =
        assertDoesNotThrow(
            () -> executor.execute("s-1", "agent-x", new ToolCallRequest("http_get", "{}")));

    assertTrue(result.success());
    assertEquals("ok", result.content());
    verify(httpGet, times(1)).execute(any());
    verify(auditor, times(1))
        .record(eq("s-1"), eq("http_get"), anyString(), eq("ok"), eq(true), isNull(), anyLong());
  }

  @Test
  @DisplayName("工具失败且审计也失败：返回的仍是工具的真实错误")
  void auditFailureOnFailPathKeepsToolError() {
    when(httpGet.execute(any())).thenThrow(new RuntimeException("connect timeout"));
    doThrow(new IllegalStateException("audit unavailable"))
        .when(auditor)
        .record(any(), anyString(), any(), any(), eq(false), anyString(), anyLong());

    ToolResult result =
        assertDoesNotThrow(
            () -> executor.execute("s-1", "agent-x", new ToolCallRequest("http_get", "{}")));

    assertFalse(result.success());
    assertTrue(result.errorMessage().contains("connect timeout")); // 审计异常不掩盖工具错误
  }

  @Test
  @DisplayName("失败也写 success=false 带原因，异常不吞")
  void failedExecutionRecordsFailureAuditWithReason() {
    when(httpGet.execute(any())).thenThrow(new RuntimeException("connect timeout"));

    ToolResult result =
        assertDoesNotThrow(
            () -> executor.execute("s-1", "agent-x", new ToolCallRequest("http_get", "{}")));

    // 异常不上抛（循环不中断），但也绝不静默：失败结果带原因 + 审计留痕
    assertFalse(result.success());
    assertTrue(result.errorMessage().contains("connect timeout"));
    verify(auditor)
        .record(
            eq("s-1"),
            eq("http_get"),
            anyString(),
            isNull(),
            eq(false),
            contains("connect timeout"),
            anyLong());
  }

  @Test
  @DisplayName("工具返回失败 ToolResult 时同样落 success=false 审计")
  void toolReturnedFailureIsAuditedAsFailure() {
    when(httpGet.execute(any())).thenReturn(ToolResult.error("域名不在白名单", false));

    ToolResult result = executor.execute("s-1", "agent-x", new ToolCallRequest("http_get", "{}"));

    assertFalse(result.success());
    verify(auditor)
        .record(
            eq("s-1"),
            eq("http_get"),
            anyString(),
            isNull(),
            eq(false),
            contains("白名单"),
            anyLong());
  }

  @Test
  @DisplayName("未注册的工具名：失败结果 + success=false 审计（不抛异常）")
  void unknownToolNameFailsAndAudits() {
    ToolResult result =
        executor.execute("s-1", "agent-x", new ToolCallRequest("no_such_tool", "{}"));

    assertFalse(result.success());
    assertTrue(result.errorMessage().contains("no_such_tool"), "报错点名未知工具");
    verify(auditor)
        .record(
            eq("s-1"),
            eq("no_such_tool"),
            anyString(),
            isNull(),
            eq(false),
            contains("no_such_tool"),
            anyLong());
  }

  @Test
  @DisplayName("入参不是合法 JSON：失败结果 + 审计留痕")
  void malformedArgumentsJsonFailsAndAudits() {
    ToolResult result =
        executor.execute("s-1", "agent-x", new ToolCallRequest("http_get", "not-json{{{"));

    assertFalse(result.success());
    verify(auditor)
        .record(
            eq("s-1"), eq("http_get"), anyString(), isNull(), eq(false), anyString(), anyLong());
  }
}
