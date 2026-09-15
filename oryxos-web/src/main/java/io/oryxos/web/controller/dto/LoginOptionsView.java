package io.oryxos.web.controller.dto;

/**
 * 登录页可用方式（040，GET /api/v1/auth/login-options 返回）：登录前即可匿名访问（豁免子树）， 前端据此显隐「企业账号登录」入口。
 *
 * @param oidcEnabled OIDC/SSO 是否启用
 */
public record LoginOptionsView(boolean oidcEnabled) {}
