package io.oryxos.web;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.oryxos.web.error.AgentTimeoutException;
import io.oryxos.web.error.ProviderUnavailableException;
import io.oryxos.web.error.ResourceNotFoundException;
import io.oryxos.web.error.SessionNotFoundException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * 课件《第26节》验收 harness：GlobalExceptionHandlerTest——每类异常映射到约定状态码、响应体都是统一 ApiResponse， 关键回归"内部异常细节绝不出现在
 * 500 响应里"。用一个内联抛异常的 dummy controller + 真 advice 跑 standalone MockMvc。
 */
class GlobalExceptionHandlerTest {

  private MockMvc mvc;
  private ListAppender<ILoggingEvent> logs;

  @BeforeEach
  void setUp() {
    logs = new ListAppender<>();
    logs.start();
    ((Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class)).addAppender(logs);
    mvc =
        MockMvcBuilders.standaloneSetup(new ThrowingController())
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
  }

  @AfterEach
  void tearDown() {
    ((Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class)).detachAppender(logs);
    logs.stop();
  }

  @Test
  @DisplayName("非法参数_映射400且统一信封")
  void badRequest_maps400() throws Exception {
    mvc.perform(MockMvcRequestBuilders.get("/throw/bad"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400))
        .andExpect(jsonPath("$.message").value("参数不对"))
        .andExpect(jsonPath("$.timestamp").exists());
  }

  @Test
  @DisplayName("会话/资源不存在_映射404")
  void notFound_maps404() throws Exception {
    mvc.perform(MockMvcRequestBuilders.get("/throw/session"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(404));
    mvc.perform(MockMvcRequestBuilders.get("/throw/resource"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(404));
  }

  @Test
  @DisplayName("Provider故障_映射503")
  void providerDown_maps503() throws Exception {
    mvc.perform(MockMvcRequestBuilders.get("/throw/provider"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value(503));
  }

  @Test
  @DisplayName("Agent调用超时_映射504")
  void timeout_maps504() throws Exception {
    mvc.perform(MockMvcRequestBuilders.get("/throw/timeout"))
        .andExpect(status().isGatewayTimeout())
        .andExpect(jsonPath("$.code").value(504));
  }

  @Test
  @DisplayName("Servlet multipart 超限映射 413 且不泄露底层异常")
  void maxUploadSize_maps413() throws Exception {
    mvc.perform(MockMvcRequestBuilders.get("/throw/upload-too-large"))
        .andExpect(status().isPayloadTooLarge())
        .andExpect(jsonPath("$.code").value(413))
        .andExpect(jsonPath("$.message").value("Skill package exceeds upload limits"))
        .andExpect(content().string(not(containsString("Maximum upload size"))));
  }

  @Test
  @DisplayName("内部异常细节_绝不能出现在500响应里")
  void internalDetailsNeverLeakIn500() throws Exception {
    mvc.perform(MockMvcRequestBuilders.get("/throw/internal"))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.code").value(500))
        .andExpect(jsonPath("$.message").value("Internal server error")) // 统一话术
        .andExpect(content().string(not(containsString("jdbc:sqlite")))); // 连接串一个字不漏

    String rendered =
        logs.list.stream()
            .map(event -> event.getFormattedMessage() + String.valueOf(event.getKeyValuePairs()))
            .reduce("", String::concat);
    assertFalse(rendered.contains("jdbc:sqlite"));
    assertFalse(rendered.contains("/data/oryxos.db"));
  }

  @Test
  @DisplayName("未分类 IllegalStateException 按内部故障映射 500 并脱敏")
  void unclassifiedIllegalState_mapsGeneric500() throws Exception {
    mvc.perform(MockMvcRequestBuilders.get("/throw/illegal-state"))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.code").value(500))
        .andExpect(jsonPath("$.message").value("Internal server error"))
        .andExpect(content().string(not(containsString("/private/catalog"))));

    String rendered =
        logs.list.stream()
            .map(event -> event.getFormattedMessage() + String.valueOf(event.getKeyValuePairs()))
            .reduce("", String::concat);
    assertFalse(rendered.contains("/private/catalog"));
  }

  /** 内联 dummy：每个路径抛一种异常，交给真 GlobalExceptionHandler 翻译。 */
  @RestController
  static class ThrowingController {
    @GetMapping("/throw/bad")
    void bad() {
      throw new IllegalArgumentException("参数不对");
    }

    @GetMapping("/throw/session")
    void session() {
      throw new SessionNotFoundException("s-x");
    }

    @GetMapping("/throw/resource")
    void resource() {
      throw new ResourceNotFoundException("Agent 不存在: x");
    }

    @GetMapping("/throw/provider")
    void provider() {
      throw new ProviderUnavailableException("deepseek 不可用");
    }

    @GetMapping("/throw/timeout")
    void timeout() {
      throw new AgentTimeoutException("Agent 调用超过 60 秒");
    }

    @GetMapping("/throw/upload-too-large")
    void uploadTooLarge() {
      throw new MaxUploadSizeExceededException(10);
    }

    @GetMapping("/throw/internal")
    void internal() {
      throw new RuntimeException("jdbc:sqlite:/data/oryxos.db connect failed");
    }

    @GetMapping("/throw/illegal-state")
    void illegalState() {
      throw new IllegalStateException("/private/catalog scan failed");
    }
  }
}
