package io.oryxos.web.controller;

import io.oryxos.core.memory.MemoryService;
import io.oryxos.web.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 只读：返回长期记忆全文（MEMORY.md）。 */
@edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
    value = "SPRING_ENDPOINT",
    justification =
        "core-stage web API is unauthenticated by design (internal network + gateway); auth is extension-phase")
@RestController
@RequestMapping("/api/v1/memory")
public class MemoryApiController {

  private final MemoryService memoryService;

  public MemoryApiController(MemoryService memoryService) {
    this.memoryService = memoryService;
  }

  @GetMapping
  public ApiResponse<String> read() {
    return ApiResponse.ok(memoryService.readAll());
  }
}
