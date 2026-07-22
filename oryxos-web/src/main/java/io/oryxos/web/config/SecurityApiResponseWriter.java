package io.oryxos.web.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.oryxos.web.common.ApiResponse;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.MediaType;

/** Writes Spring Security failures using the same JSON envelope as controller failures. */
final class SecurityApiResponseWriter {

  private final ObjectMapper objectMapper;

  SecurityApiResponseWriter(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  void write(HttpServletResponse response, int status, String message) throws IOException {
    response.setStatus(status);
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    objectMapper.writeValue(response.getWriter(), ApiResponse.error(status, message));
  }
}
