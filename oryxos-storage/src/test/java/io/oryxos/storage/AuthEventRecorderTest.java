package io.oryxos.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;

/**
 * 040 验收 harness：AuthEventRecorder——三纪律钉死（MDC traceId / 字段截断 / 写失败不上抛）。
 * 守：审计失败绝不阻断登录主链路（FR-007）、null 容忍、字段上限。
 */
class AuthEventRecorderTest {

  private AuthEventRepository repository;
  private AuthEventRecorder recorder;

  @BeforeEach
  void setUp() {
    repository = mock(AuthEventRepository.class);
    recorder = new AuthEventRecorder(repository);
  }

  @AfterEach
  void clearMdc() {
    MDC.remove("traceId");
  }

  @Test
  @DisplayName("record_字段齐全落库且traceId从MDC读")
  void record_fullFields() {
    MDC.put("traceId", "trace-abc");
    recorder.record(
        new AuthEventRecorder.AuthEventData(
            AuthEventRecorder.LOGIN_SUCCESS,
            AuthEventRecorder.METHOD_OIDC,
            "alice",
            "https://idp/realm",
            "sub-1",
            "VIEWER,ADMIN",
            null,
            "10.0.0.1"));

    ArgumentCaptor<AuthEvent> captor = ArgumentCaptor.forClass(AuthEvent.class);
    verify(repository).save(captor.capture());
    AuthEvent saved = captor.getValue();
    assertThat(saved.getEventType()).isEqualTo("login_success");
    assertThat(saved.getAuthMethod()).isEqualTo("oidc");
    assertThat(saved.getUsername()).isEqualTo("alice");
    assertThat(saved.getExternalIssuer()).isEqualTo("https://idp/realm");
    assertThat(saved.getExternalSubject()).isEqualTo("sub-1");
    assertThat(saved.getRoles()).isEqualTo("VIEWER,ADMIN");
    assertThat(saved.getSourceIp()).isEqualTo("10.0.0.1");
    assertThat(saved.getTraceId()).isEqualTo("trace-abc");
  }

  @Test
  @DisplayName("record_超长字段截断到列上限")
  void record_truncatesOverlongFields() {
    recorder.record(
        new AuthEventRecorder.AuthEventData(
            AuthEventRecorder.LOGIN_FAILURE,
            AuthEventRecorder.METHOD_OIDC,
            "u".repeat(200),
            "i".repeat(500),
            "s".repeat(500),
            "r".repeat(500),
            "f".repeat(200),
            "p".repeat(200)));

    ArgumentCaptor<AuthEvent> captor = ArgumentCaptor.forClass(AuthEvent.class);
    verify(repository).save(captor.capture());
    AuthEvent saved = captor.getValue();
    assertThat(saved.getUsername()).hasSize(64);
    assertThat(saved.getExternalIssuer()).hasSize(255);
    assertThat(saved.getExternalSubject()).hasSize(255);
    assertThat(saved.getRoles()).hasSize(255);
    assertThat(saved.getFailureReason()).hasSize(64);
    assertThat(saved.getSourceIp()).hasSize(64);
  }

  @Test
  @DisplayName("record_写库抛异常_吞掉不上抛（登录主链路不受影响）")
  void record_saveFailure_swallowed() {
    when(repository.save(any(AuthEvent.class))).thenThrow(new RuntimeException("db down"));

    assertThatCode(
            () ->
                recorder.record(
                    new AuthEventRecorder.AuthEventData(
                        AuthEventRecorder.LOGOUT,
                        AuthEventRecorder.METHOD_LOCAL,
                        "alice",
                        null,
                        null,
                        null,
                        null,
                        null)))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("record_null数据或缺必填字段_静默忽略")
  void record_nullTolerant() {
    assertThatCode(() -> recorder.record(null)).doesNotThrowAnyException();
    recorder.record(
        new AuthEventRecorder.AuthEventData(null, "local", null, null, null, null, null, null));
    recorder.record(
        new AuthEventRecorder.AuthEventData("logout", null, null, null, null, null, null, null));
    verify(repository, never()).save(any());
  }
}
