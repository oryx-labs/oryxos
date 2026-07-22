package io.oryxos.web.controller.dto;

/** Public authentication status for the management console bootstrap. */
public record AuthStatusView(boolean configured, boolean authenticated, String username) {}
