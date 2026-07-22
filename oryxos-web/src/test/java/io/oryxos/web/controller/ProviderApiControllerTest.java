package io.oryxos.web.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.oryxos.core.provider.ProviderDef;
import io.oryxos.core.provider.ProviderRegistry;
import io.oryxos.web.GlobalExceptionHandler;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** providers 端点切片：CRUD 薄转发（冲突/非法→400、不存在→404，统一 ApiResponse）。 */
class ProviderApiControllerTest {

  private ProviderRegistry registry;
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    registry = mock(ProviderRegistry.class);
    mvc =
        MockMvcBuilders.standaloneSetup(new ProviderApiController(registry))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
  }

  @Test
  @DisplayName("create 成功_返回 provider 视图（api-key 明文回显）")
  void create_success_returnsView() throws Exception {
    when(registry.exists("kimi")).thenReturn(false);
    when(registry.save(any()))
        .thenReturn(new ProviderDef("kimi", "sk-x", "https://api.moonshot.cn", "月之暗面"));

    mvc.perform(
            post("/api/v1/providers")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"name\":\"kimi\",\"apiKey\":\"sk-x\",\"baseUrl\":\"https://api.moonshot.cn\",\"description\":\"月之暗面\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.name").value("kimi"))
        .andExpect(jsonPath("$.data.apiKey").value("sk-x"));
  }

  @Test
  @DisplayName("create 名字冲突_返回400_不落库")
  void create_conflict_returns400() throws Exception {
    when(registry.exists("dup")).thenReturn(true);

    mvc.perform(
            post("/api/v1/providers")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"dup\",\"apiKey\":\"k\",\"baseUrl\":\"https://x\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400));
    verify(registry, never()).save(any());
  }

  @Test
  @DisplayName("create 非 mock 缺 base-url_返回400")
  void create_missingBaseUrl_returns400() throws Exception {
    when(registry.exists("x")).thenReturn(false);

    mvc.perform(
            post("/api/v1/providers")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"x\",\"apiKey\":\"k\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400));
    verify(registry, never()).save(any());
  }

  @Test
  @DisplayName("create mock_免 base-url 也可")
  void create_mock_allowsNoBaseUrl() throws Exception {
    when(registry.exists("mock")).thenReturn(false);
    when(registry.save(any())).thenReturn(new ProviderDef("mock", null, null, null));

    mvc.perform(
            post("/api/v1/providers")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"mock\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.name").value("mock"));
  }

  @Test
  @DisplayName("get 不存在_返回404")
  void get_unknown_returns404() throws Exception {
    when(registry.find("ghost")).thenReturn(Optional.empty());

    mvc.perform(get("/api/v1/providers/ghost"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(404));
  }

  @Test
  @DisplayName("delete 不存在_返回404_不触发删除")
  void delete_unknown_returns404() throws Exception {
    when(registry.exists("ghost")).thenReturn(false);

    mvc.perform(delete("/api/v1/providers/ghost")).andExpect(status().isNotFound());
    verify(registry, never()).delete(any());
  }
}
