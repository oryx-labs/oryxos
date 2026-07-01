package io.oryxos.web.common;

import java.time.Instant;

/**
 * Unified REST response envelope returned by every OryxOS API endpoint.
 *
 * @param <T> payload type
 */
public class ApiResponse<T> {

  private final int code;
  private final String message;
  private final T data;
  private final long timestamp;

  public ApiResponse(int code, String message, T data) {
    this.code = code;
    this.message = message;
    this.data = data;
    this.timestamp = Instant.now().toEpochMilli();
  }

  /** Build a success response carrying the given payload. */
  public static <T> ApiResponse<T> ok(T data) {
    return new ApiResponse<>(0, "success", data);
  }

  /** Build an error response with a business code and message. */
  public static <T> ApiResponse<T> error(int code, String message) {
    return new ApiResponse<>(code, message, null);
  }

  public int getCode() {
    return code;
  }

  public String getMessage() {
    return message;
  }

  public T getData() {
    return data;
  }

  public long getTimestamp() {
    return timestamp;
  }

  @Override
  public String toString() {
    return "ApiResponse{code=" + code + ", message='" + message + "', timestamp=" + timestamp + '}';
  }
}
