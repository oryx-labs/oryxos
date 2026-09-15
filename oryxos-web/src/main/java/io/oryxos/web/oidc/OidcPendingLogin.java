package io.oryxos.web.oidc;

import java.time.Instant;

/**
 * 授权跳转前暂存的 PKCE state（040）。仅存内存；进程重启会使进行中的登录失效（可接受）。
 *
 * @param codeVerifier PKCE verifier
 * @param createdAt 创建时间（TTL 判定）
 */
record OidcPendingLogin(String codeVerifier, Instant createdAt) {}
