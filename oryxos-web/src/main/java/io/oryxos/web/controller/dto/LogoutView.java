package io.oryxos.web.controller.dto;

/**
 * 登出响应载荷（040 R15，POST /api/v1/auth/logout 返回）：仅 rp-initiated-logout 开启且当前会话 属 OIDC 用户时返回；其余情况 data
 * 保持 null（与既有响应逐字节一致）。
 *
 * @param idpLogoutUrl IdP 端登出地址（end_session_endpoint 拼装），前端拿到即跳转
 */
public record LogoutView(String idpLogoutUrl) {}
