package io.oryxos.web.controller.dto;

/**
 * 当前登录用户信息（012-web-auth US3，GET /api/v1/auth/me 返回）。
 *
 * @param authenticationEnabled 管理台认证是否启用
 * @param username 当前用户名；认证关闭时为空
 */
public record AuthMeView(boolean authenticationEnabled, String username) {}
