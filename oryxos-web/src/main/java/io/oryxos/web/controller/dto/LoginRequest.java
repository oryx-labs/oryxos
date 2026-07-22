package io.oryxos.web.controller.dto;

/** Login request submitted by the management console. */
public record LoginRequest(String username, String password) {}
