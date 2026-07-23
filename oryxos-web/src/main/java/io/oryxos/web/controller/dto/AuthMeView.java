package io.oryxos.web.controller.dto;

/**
 * 当前登录用户信息（012-web-auth US3，GET /api/v1/auth/me 返回）。
 *
 * @param username 用户名
 */
public record AuthMeView(String username) {}
