package io.oryxos.provider;

import com.openai.errors.OpenAIIoException;
import com.openai.errors.OpenAIRetryableException;
import com.openai.errors.OpenAIServiceException;
import java.io.IOException;
import java.util.concurrent.TimeoutException;

/**
 * 可切换性分类（023，R3）：判定一次 LLM 调用失败是否值得换备用 Provider 重试。
 *
 * <p>分类原则：换一个 Provider 有合理成功预期的才算可切换——网络/超时/5xx/限流/认证（各家凭证独立）切； 400
 * 类请求本身非法不切（FR-003）。提取不到状态码的未知异常偏向切换：多试一次的代价是一次超时， 漏切的代价是本可避免的服务中断（全败仍上抛最后错误，不吞）。
 *
 * <p>Spring AI 2.0 改用 openai-java：HTTP 失败以 {@link OpenAIServiceException}（及 IO/可重试包装）呈现， 不再依赖
 * {@code RestClient}/{@code spring-ai-retry} 异常族。
 */
final class FallbackClassifier {

  /** 5xx 下界：服务端故障一律可切。 */
  private static final int SERVER_ERROR_FLOOR = 500;

  private FallbackClassifier() {}

  /** true = 值得换下一个候选重试。 */
  static boolean isSwitchable(RuntimeException e) {
    Throwable t = e;
    while (t != null) {
      if (t instanceof OpenAIServiceException svc) {
        return switchableStatus(svc.statusCode());
      }
      // SDK 瞬时/IO：换端点最典型收益
      if (t instanceof OpenAIIoException
          || t instanceof OpenAIRetryableException
          || t instanceof IOException
          || t instanceof TimeoutException) {
        return true;
      }
      // 兼容旧 message 前缀形态（"400 - {json}"），以防上层仍按该格式包装
      Integer code = leadingStatus(t.getMessage());
      if (code != null) {
        return switchableStatus(code);
      }
      t = t.getCause();
    }
    return true; // 无状态码可依：宁多试一次备用（R3）
  }

  private static final java.util.regex.Pattern LEADING_STATUS =
      java.util.regex.Pattern.compile("^(\\d{3}) - ");

  /** 解析异常 message 前缀的 HTTP 状态码；无则 null。 */
  private static Integer leadingStatus(String message) {
    if (message == null) {
      return null;
    }
    java.util.regex.Matcher matcher = LEADING_STATUS.matcher(message);
    return matcher.find() ? Integer.valueOf(matcher.group(1)) : null;
  }

  private static boolean switchableStatus(int code) {
    if (code >= SERVER_ERROR_FLOOR) {
      return true; // 服务端故障
    }
    // 429 限流=换配额；401/403 本家凭证问题=换家换凭证；408 请求超时
    return code == 429 || code == 401 || code == 403 || code == 408;
  }
}
