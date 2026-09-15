package io.oryxos.web.security.oidc;

/**
 * OIDC 登录失败分类（040 contracts）：既是登录页 {@code ?error=} 的对外错误码，也是 {@code auth_events.failure_reason}
 * 的落库值。分类枚举是对外错误的全部信息量——令牌原文、IdP 响应体、 堆栈绝不外泄（FR-005/SC-006）。
 */
public enum OidcErrorCode {
  /** discovery/JWKS 不可达（IdP 宕机/网络故障）。 */
  IDP_UNREACHABLE("idp_unreachable"),
  /** IdP 回调携带 error 参数（用户拒绝授权等）。 */
  IDP_ERROR("idp_error"),
  /** state 未知/已消费（重放）/过期。 */
  INVALID_STATE("invalid_state"),
  /** code 换 token 失败（HTTP 错误/无 id_token）。 */
  TOKEN_EXCHANGE_FAILED("token_exchange_failed"),
  /** ID Token 签名验证失败（含未知 kid）。 */
  INVALID_SIGNATURE("invalid_signature"),
  /** iss/aud/nonce/iat 等 claims 校验失败。 */
  INVALID_CLAIMS("invalid_claims"),
  /** ID Token 已过期（超出时钟偏移窗）。 */
  EXPIRED_TOKEN("expired_token"),
  /** 身份映射/JIT 供给失败。 */
  PROVISIONING_FAILED("provisioning_failed");

  private final String code;

  OidcErrorCode(String code) {
    this.code = code;
  }

  /** 对外错误码（登录页查询参数与审计 failure_reason 共用）。 */
  public String code() {
    return code;
  }
}
