package io.oryxos.web.controller.dto;

/**
 * 登录请求体（012-web-auth US3）。
 *
 * @param username 用户名
 * @param password 明文密码（仅请求传输，服务端校验后不存）
 */
public record LoginRequest(String username, String password) {}
