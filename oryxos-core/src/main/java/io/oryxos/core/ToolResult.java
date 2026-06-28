package io.oryxos.core;

public record ToolResult(boolean success, String content, String errorMessage, boolean retryable) {
    public static ToolResult ok(String content) {
        return new ToolResult(true, content, null, false);
    }
    public static ToolResult error(String errorMessage, boolean retryable) {
        return new ToolResult(false, null, errorMessage, retryable);
    }
}
