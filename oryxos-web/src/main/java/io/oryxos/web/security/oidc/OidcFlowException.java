package io.oryxos.web.security.oidc;

/**
 * OIDC 流程失败（040）：携带对外错误分类。message 只允许分类级描述，NEVER 含令牌内容/IdP 响应体 （FR-005；诊断细节由抛出点以 WARN
 * 日志留在服务端，同样不含令牌）。
 */
public class OidcFlowException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final OidcErrorCode code;

  public OidcFlowException(OidcErrorCode code, String message) {
    super(message);
    this.code = code;
  }

  public OidcFlowException(OidcErrorCode code, String message, Throwable cause) {
    super(message, cause);
    this.code = code;
  }

  public OidcErrorCode code() {
    return code;
  }
}
