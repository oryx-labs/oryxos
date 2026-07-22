package io.oryxos.web.controller.dto;

/** CSRF token details needed by same-origin browser clients. */
public record CsrfTokenView(String headerName, String token) {}
