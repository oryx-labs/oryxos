package io.oryxos.web.controller;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.oryxos.core.skill.SkillConflictException;
import io.oryxos.core.skill.SkillDescriptor;
import io.oryxos.core.skill.SkillImportException;
import io.oryxos.core.skill.SkillManagementService;
import io.oryxos.core.skill.SkillMetadata;
import io.oryxos.core.skill.SkillPackageTooLargeException;
import io.oryxos.core.skill.SkillSource;
import io.oryxos.core.skill.SkillStatus;
import io.oryxos.core.skill.SkillValidationCode;
import io.oryxos.core.skill.SkillValidationError;
import io.oryxos.core.skill.SkillValidationException;
import io.oryxos.web.GlobalExceptionHandler;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** HTTP contract tests for Agent-private Skill discovery and lifecycle management. */
class AgentSkillApiControllerTest {

  private static final Instant UPDATED_AT = Instant.parse("2026-07-22T10:30:00Z");

  private SkillManagementService service;
  private AgentSkillApiController controller;
  private MockMvc mvc;
  private ListAppender<ILoggingEvent> managementLogs;

  @BeforeEach
  void setUp() {
    service = mock(SkillManagementService.class);
    controller = new AgentSkillApiController(service);
    mvc =
        MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    managementLogs = new ListAppender<>();
    managementLogs.start();
    ((Logger) LoggerFactory.getLogger(SkillManagementService.class)).addAppender(managementLogs);
  }

  @AfterEach
  void tearDown() {
    ((Logger) LoggerFactory.getLogger(SkillManagementService.class)).detachAppender(managementLogs);
    managementLogs.stop();
  }

  @Test
  @DisplayName("GET collection 返回按 directoryName 排序的三态 summary")
  void list_returnsSortedSummaryContract() throws Exception {
    when(service.list("demo"))
        .thenReturn(List.of(valid("weather", SkillSource.UPLOAD), invalid("broken")));

    mvc.perform(get("/api/v1/agents/demo/skills"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0))
        .andExpect(jsonPath("$.message").value("success"))
        .andExpect(jsonPath("$.timestamp").isNumber())
        .andExpect(jsonPath("$.data[0].directoryName").value("broken"))
        .andExpect(jsonPath("$.data[0].name").value("broken"))
        .andExpect(jsonPath("$.data[0].status").value("invalid"))
        .andExpect(jsonPath("$.data[0].configuredEnabled").value(true))
        .andExpect(jsonPath("$.data[0].catalogIncluded").value(false))
        .andExpect(jsonPath("$.data[0].source").value("workspace"))
        .andExpect(jsonPath("$.data[0].validationError.code").value("MISSING_ENTRYPOINT"))
        .andExpect(jsonPath("$.data[1].directoryName").value("weather"))
        .andExpect(jsonPath("$.data[1].status").value("enabled"))
        .andExpect(jsonPath("$.data[1].source").value("upload"))
        .andExpect(jsonPath("$.data[1].updatedAt").value("2026-07-22T10:30:00Z"))
        .andExpect(jsonPath("$.data[1].entrypoint").doesNotExist())
        .andExpect(content().string(not(containsString("skill body must stay private"))))
        .andExpect(content().string(not(containsString("/Users/"))))
        .andExpect(content().string(not(containsString("/private/"))));
  }

  @Test
  @DisplayName("GET member 只返回相对 entry/resources，不返回正文或绝对内部路径")
  void get_returnsSafeDetailContract() throws Exception {
    when(service.get("demo", "weather")).thenReturn(valid("weather", SkillSource.UPLOAD));

    mvc.perform(get("/api/v1/agents/demo/skills/weather"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.name").value("weather"))
        .andExpect(jsonPath("$.data.entrypoint").value("skills/weather/SKILL.md"))
        .andExpect(jsonPath("$.data.license").value("Apache-2.0"))
        .andExpect(jsonPath("$.data.compatibility").value("Requires read_file"))
        .andExpect(jsonPath("$.data.metadata.author").value("example-team"))
        .andExpect(jsonPath("$.data.allowedTools").value("read_file shell"))
        .andExpect(jsonPath("$.data.resources[0]").value("SKILL.md"))
        .andExpect(jsonPath("$.data.resources[1]").value("references/api.md"))
        .andExpect(jsonPath("$.data.fileCount").value(2))
        .andExpect(jsonPath("$.data.totalBytes").value(4812))
        .andExpect(content().string(not(containsString("skill body must stay private"))))
        .andExpect(content().string(not(containsString("/Users/"))))
        .andExpect(content().string(not(containsString("/private/"))));

    verify(service).get("demo", "weather");
  }

  @Test
  @DisplayName("member 必须是安全单段，非法值不进入 core service")
  void get_rejectsUnsafeMemberBeforeCore() {
    for (String unsafe :
        List.of(
            "",
            ".",
            "..",
            "a/b",
            "a\\b",
            "bad\0name",
            "bad\nname",
            "bad\u202ename",
            "bad\u2028name")) {
      assertThrows(IllegalArgumentException.class, () -> controller.get("demo", unsafe));
    }
    verifyNoInteractions(service);
    assertNoManagementLogs();
  }

  @Test
  @DisplayName("URL 解码后的换行控制字符由 Web 层提前拒绝")
  void get_encodedControlCharacter_returns400WithoutCoreCall() throws Exception {
    mvc.perform(get("/api/v1/agents/demo/skills/{skillName}", "bad\nname"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400));

    verifyNoInteractions(service);
    assertNoManagementLogs();
  }

  @Test
  @DisplayName("Agent 或 Skill 不存在映射 404")
  void get_missingResource_returns404() throws Exception {
    when(service.get("demo", "missing"))
        .thenThrow(new NoSuchElementException("Skill does not exist: demo/missing"));

    mvc.perform(get("/api/v1/agents/demo/skills/missing"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(404))
        .andExpect(jsonPath("$.message").value("Skill does not exist: demo/missing"));
  }

  @Test
  @DisplayName("multipart POST 以 InputStream 和原文件名调用 core，不传 MultipartFile")
  void importSkill_passesStreamAndReturnsDetail() throws Exception {
    byte[] zip = {'P', 'K', 3, 4, 1, 2, 3};
    AtomicReference<byte[]> observed = new AtomicReference<>();
    doAnswer(
            invocation -> {
              observed.set(invocation.<java.io.InputStream>getArgument(1).readAllBytes());
              return valid("weather", SkillSource.UPLOAD);
            })
        .when(service)
        .importSkill(eq("demo"), any(java.io.InputStream.class), eq("client-name.zip"));

    mvc.perform(
            multipart("/api/v1/agents/demo/skills")
                .file(new MockMultipartFile("file", "client-name.zip", "application/zip", zip)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.directoryName").value("weather"))
        .andExpect(jsonPath("$.data.status").value("enabled"));

    assertArrayEquals(zip, observed.get());
  }

  @Test
  @DisplayName("multipart 缺 file part 在 Web 层返回 400，不调用 core")
  void importSkill_missingPart_returns400WithoutCoreCall() throws Exception {
    mvc.perform(multipart("/api/v1/agents/demo/skills"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400));

    verifyNoInteractions(service);
    assertNoManagementLogs();
  }

  @Test
  @DisplayName("multipart 空 file 在 Web 层返回 400，不调用 core")
  void importSkill_emptyPart_returns400WithoutCoreCall() throws Exception {
    mvc.perform(
            multipart("/api/v1/agents/demo/skills")
                .file(new MockMultipartFile("file", "empty.zip", "application/zip", new byte[0])))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400));

    verifyNoInteractions(service);
    assertNoManagementLogs();
  }

  @Test
  @DisplayName("multipart 导入到不存在 Agent 映射 404")
  void importSkill_missingAgent_returns404() throws Exception {
    doThrow(new NoSuchElementException("Agent does not exist: missing"))
        .when(service)
        .importSkill(eq("missing"), any(java.io.InputStream.class), eq("weather.zip"));

    mvc.perform(
            multipart("/api/v1/agents/missing/skills")
                .file(
                    new MockMultipartFile(
                        "file", "weather.zip", "application/zip", new byte[] {1})))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(404));
  }

  @Test
  @DisplayName("Skill 校验错误映射 400，响应不泄露路径或堆栈")
  void importSkill_validationFailure_returnsSafe400() throws Exception {
    doThrow(
            new SkillValidationException(
                SkillValidationCode.INVALID_YAML, "SKILL.md frontmatter is invalid"))
        .when(service)
        .importSkill(eq("demo"), any(java.io.InputStream.class), eq("bad.zip"));

    mvc.perform(
            multipart("/api/v1/agents/demo/skills")
                .file(new MockMultipartFile("file", "bad.zip", "application/zip", new byte[] {1})))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400))
        .andExpect(jsonPath("$.message").value("SKILL.md frontmatter is invalid"))
        .andExpect(content().string(not(containsString("/Users/"))))
        .andExpect(content().string(not(containsString("/private/"))))
        .andExpect(content().string(not(containsString("Exception"))));
  }

  @Test
  @DisplayName("非 ZIP/结构错误映射 400")
  void importSkill_importFailure_returns400() throws Exception {
    SkillImportException failure =
        new SkillImportException("NOT_A_ZIP", "Skill upload is not a ZIP archive");
    doThrow(failure)
        .when(service)
        .importSkill(eq("demo"), any(java.io.InputStream.class), eq("plain.txt"));

    mvc.perform(
            multipart("/api/v1/agents/demo/skills")
                .file(new MockMultipartFile("file", "plain.txt", "text/plain", new byte[] {1})))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400))
        .andExpect(jsonPath("$.message").value("Skill upload is not a ZIP archive"));
  }

  @Test
  @DisplayName("任意状态或 unmanaged 同名冲突映射 409")
  void importSkill_nameConflict_returns409() throws Exception {
    SkillConflictException conflict = new SkillConflictException("Skill already exists: weather");
    doThrow(conflict)
        .when(service)
        .importSkill(eq("demo"), any(java.io.InputStream.class), eq("weather.zip"));

    mvc.perform(
            multipart("/api/v1/agents/demo/skills")
                .file(
                    new MockMultipartFile(
                        "file", "weather.zip", "application/zip", new byte[] {1})))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value(409))
        .andExpect(jsonPath("$.message").value("Skill already exists: weather"));
  }

  @Test
  @DisplayName("压缩或展开限制超限映射 413")
  void importSkill_packageTooLarge_returns413() throws Exception {
    SkillPackageTooLargeException tooLarge =
        new SkillPackageTooLargeException(
            "ARCHIVE_TOO_LARGE", "Skill package exceeds configured limit");
    doThrow(tooLarge)
        .when(service)
        .importSkill(eq("demo"), any(java.io.InputStream.class), eq("large.zip"));

    mvc.perform(
            multipart("/api/v1/agents/demo/skills")
                .file(
                    new MockMultipartFile("file", "large.zip", "application/zip", new byte[] {1})))
        .andExpect(status().isPayloadTooLarge())
        .andExpect(jsonPath("$.code").value(413))
        .andExpect(jsonPath("$.message").value("Skill package exceeds configured limit"))
        .andExpect(content().string(not(containsString("/Users/"))))
        .andExpect(content().string(not(containsString("/private/"))));
  }

  @Test
  @DisplayName("未预期 I/O 只返回通用 500，不泄露异常细节")
  void importSkill_unexpectedIo_returnsGeneric500() throws Exception {
    doThrow(new UncheckedIOException(new IOException("/private/secret staging failed")))
        .when(service)
        .importSkill(eq("demo"), any(java.io.InputStream.class), eq("bad.zip"));

    mvc.perform(
            multipart("/api/v1/agents/demo/skills")
                .file(new MockMultipartFile("file", "bad.zip", "application/zip", new byte[] {1})))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.code").value(500))
        .andExpect(jsonPath("$.message").value("Internal server error"))
        .andExpect(content().string(not(containsString("/private/secret"))))
        .andExpect(content().string(not(containsString("UncheckedIOException"))));
  }

  @Test
  @DisplayName("catalog 状态/I/O 故障只返回通用 500，不复用 provider 的 503 映射")
  void importSkill_catalogIllegalState_returnsGeneric500() throws Exception {
    doThrow(new IllegalStateException("/private/catalog should not leak"))
        .when(service)
        .importSkill(eq("demo"), any(java.io.InputStream.class), eq("bad.zip"));

    mvc.perform(
            multipart("/api/v1/agents/demo/skills")
                .file(new MockMultipartFile("file", "bad.zip", "application/zip", new byte[] {1})))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.code").value(500))
        .andExpect(jsonPath("$.message").value("Internal server error"))
        .andExpect(content().string(not(containsString("/private/catalog"))));
  }

  @Test
  @DisplayName("PUT enabled 只接受原生 JSON boolean，缺失/字符串/数字/null/坏 JSON 均为 400")
  void setEnabled_requiresStrictJsonBoolean() throws Exception {
    for (String body :
        List.of(
            "{}",
            "{\"enabled\":\"false\"}",
            "{\"enabled\":0}",
            "{\"enabled\":null}",
            "null",
            "{")) {
      mvc.perform(
              put("/api/v1/agents/demo/skills/weather")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(body))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value(400));
    }

    verifyNoInteractions(service);
    assertNoManagementLogs();
  }

  @Test
  @DisplayName("PUT 重复禁用幂等返回 disabled 详情")
  void setEnabled_repeatedDisable_returnsSameDisabledDetail() throws Exception {
    when(service.setEnabled("demo", "weather", false)).thenReturn(disabled("weather"));

    for (int attempt = 0; attempt < 2; attempt++) {
      mvc.perform(
              put("/api/v1/agents/demo/skills/weather")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"enabled\":false}"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.status").value("disabled"))
          .andExpect(jsonPath("$.data.configuredEnabled").value(false))
          .andExpect(jsonPath("$.data.catalogIncluded").value(false));
    }

    verify(service, times(2)).setEnabled("demo", "weather", false);
  }

  @Test
  @DisplayName("PUT 启用 invalid Skill 映射安全 400，不泄露路径或堆栈")
  void setEnabled_invalidEnable_returnsSafe400() throws Exception {
    when(service.setEnabled("demo", "broken", true))
        .thenThrow(
            new SkillValidationException(
                SkillValidationCode.INVALID_YAML, "Skill cannot be enabled: INVALID_YAML"));

    mvc.perform(
            put("/api/v1/agents/demo/skills/broken")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":true}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400))
        .andExpect(jsonPath("$.message").value("Skill cannot be enabled: INVALID_YAML"))
        .andExpect(content().string(not(containsString("/Users/"))))
        .andExpect(content().string(not(containsString("/private/"))))
        .andExpect(content().string(not(containsString("Exception"))));
  }

  @Test
  @DisplayName("PUT Agent 或 Skill 不存在映射 404")
  void setEnabled_missingResource_returns404() throws Exception {
    when(service.setEnabled("demo", "missing", true))
        .thenThrow(new NoSuchElementException("Skill does not exist: demo/missing"));

    mvc.perform(
            put("/api/v1/agents/demo/skills/missing")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":true}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(404));
  }

  @Test
  @DisplayName("DELETE enabled/disabled/invalid 都返回 data=null 的 epoch-millis 信封")
  void delete_allStates_returnsNullDataAndEpochMillis() throws Exception {
    for (String skill : List.of("enabled-skill", "disabled-skill", "invalid-skill")) {
      mvc.perform(delete("/api/v1/agents/demo/skills/{skillName}", skill))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.code").value(0))
          .andExpect(jsonPath("$.message").value("success"))
          .andExpect(jsonPath("$.data").value(nullValue()))
          .andExpect(jsonPath("$.timestamp").value(greaterThan(1_000_000_000_000L)));
      verify(service).delete("demo", skill);
    }
  }

  @Test
  @DisplayName("DELETE 不存在 Skill 映射 404")
  void delete_missingResource_returns404() throws Exception {
    doThrow(new NoSuchElementException("Skill does not exist: demo/missing"))
        .when(service)
        .delete("demo", "missing");

    mvc.perform(delete("/api/v1/agents/demo/skills/missing"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(404));
  }

  @Test
  @DisplayName("DELETE URL 解码后的非法 member 在 Web 层拒绝，不进入 core")
  void delete_unsafeMember_returns400WithoutCoreCall() throws Exception {
    mvc.perform(delete("/api/v1/agents/demo/skills/{skillName}", "bad\nname"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400));

    verifyNoInteractions(service);
    assertNoManagementLogs();
  }

  private void assertNoManagementLogs() {
    assertTrue(managementLogs.list.isEmpty(), "Web-layer rejection must not emit mutation logs");
  }

  private static SkillDescriptor valid(String name, SkillSource source) {
    SkillMetadata metadata = metadata(name);
    return new SkillDescriptor(
        "demo",
        name,
        metadata,
        SkillStatus.ENABLED,
        true,
        source,
        UPDATED_AT,
        null,
        metadata.relativeEntry(),
        List.of("references/api.md", "SKILL.md"),
        2,
        4812,
        true);
  }

  private static SkillDescriptor disabled(String name) {
    SkillMetadata metadata = metadata(name);
    return new SkillDescriptor(
        "demo",
        name,
        metadata,
        SkillStatus.DISABLED,
        false,
        SkillSource.WORKSPACE,
        UPDATED_AT,
        null,
        metadata.relativeEntry(),
        List.of("references/api.md", "SKILL.md"),
        2,
        4812,
        false);
  }

  private static SkillMetadata metadata(String name) {
    return new SkillMetadata(
        name,
        "查询天气并给出出行建议",
        "Apache-2.0",
        "Requires read_file",
        Map.of("author", "example-team"),
        "read_file shell",
        Path.of("/private/internal", name, "SKILL.md"),
        "skills/" + name + "/SKILL.md");
  }

  private static SkillDescriptor invalid(String directoryName) {
    return new SkillDescriptor(
        "demo",
        directoryName,
        null,
        SkillStatus.INVALID,
        true,
        SkillSource.WORKSPACE,
        UPDATED_AT,
        new SkillValidationError(SkillValidationCode.MISSING_ENTRYPOINT, "SKILL.md is missing"),
        null,
        List.of(),
        0,
        0,
        false);
  }
}
