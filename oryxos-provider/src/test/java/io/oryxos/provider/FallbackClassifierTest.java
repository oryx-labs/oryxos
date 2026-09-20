package io.oryxos.provider;

import static org.assertj.core.api.Assertions.assertThat;

import com.openai.core.http.Headers;
import com.openai.errors.BadRequestException;
import com.openai.errors.InternalServerException;
import com.openai.errors.OpenAIIoException;
import com.openai.errors.RateLimitException;
import com.openai.errors.UnauthorizedException;
import com.openai.errors.UnexpectedStatusCodeException;
import java.io.IOException;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;

/** 023 US1：可切换性分类表逐行钉死（R3）；Spring AI 2.0 对齐 openai-java 异常族。 */
class FallbackClassifierTest {

  private static RuntimeException status(int code) {
    Headers headers = Headers.builder().build();
    return switch (code) {
      case 400 -> BadRequestException.builder().headers(headers).build();
      case 401 -> UnauthorizedException.builder().headers(headers).build();
      case 429 -> RateLimitException.builder().headers(headers).build();
      case 500, 502, 503 ->
          InternalServerException.builder().statusCode(code).headers(headers).build();
      default -> UnexpectedStatusCodeException.builder().statusCode(code).headers(headers).build();
    };
  }

  @Test
  void 服务端与限流认证类_可切换() {
    assertThat(FallbackClassifier.isSwitchable(status(500))).isTrue();
    assertThat(FallbackClassifier.isSwitchable(status(502))).isTrue();
    assertThat(FallbackClassifier.isSwitchable(status(503))).isTrue();
    assertThat(FallbackClassifier.isSwitchable(status(429))).isTrue(); // 限流=换配额
    assertThat(FallbackClassifier.isSwitchable(status(401))).isTrue(); // 本家凭证问题=换家
    assertThat(FallbackClassifier.isSwitchable(status(403))).isTrue();
    assertThat(FallbackClassifier.isSwitchable(status(408))).isTrue();
  }

  @Test
  void 业务性失败_不切换() {
    assertThat(FallbackClassifier.isSwitchable(status(400))).isFalse(); // 请求非法换家无意义
    assertThat(FallbackClassifier.isSwitchable(status(404))).isFalse();
    assertThat(FallbackClassifier.isSwitchable(status(422))).isFalse();
  }

  @Test
  void 网络与超时类_可切换() {
    assertThat(FallbackClassifier.isSwitchable(new OpenAIIoException("connect refused"))).isTrue();
    assertThat(
            FallbackClassifier.isSwitchable(
                new RuntimeException("wrapped", new TimeoutException("read timeout"))))
        .isTrue();
  }

  @Test
  void 异常链深埋_逐层提取() {
    RuntimeException deep400 =
        new RuntimeException("outer", new IllegalStateException(status(400)));
    assertThat(FallbackClassifier.isSwitchable(deep400)).isFalse();
    RuntimeException deepIo =
        new RuntimeException("outer", new RuntimeException(new IOException("broken pipe")));
    assertThat(FallbackClassifier.isSwitchable(deepIo)).isTrue();
  }

  @Test
  void 无状态码的未知异常_宁多试一次() {
    assertThat(FallbackClassifier.isSwitchable(new IllegalStateException("who knows"))).isTrue();
  }

  @Test
  void message前缀状态码判定_兼容旧包装形态() {
    assertThat(
            FallbackClassifier.isSwitchable(
                new RuntimeException("400 - {\"error\":{\"message\":\"invalid request body\"}}")))
        .isFalse();
    assertThat(FallbackClassifier.isSwitchable(new RuntimeException("401 - unauthorized")))
        .isTrue();
    assertThat(FallbackClassifier.isSwitchable(new RuntimeException("429 - rate limited")))
        .isTrue();
  }
}
