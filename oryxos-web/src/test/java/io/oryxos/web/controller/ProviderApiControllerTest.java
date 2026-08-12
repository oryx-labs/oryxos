package io.oryxos.web.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.oryxos.core.provider.ProviderDef;
import io.oryxos.core.provider.ProviderRegistry;
import io.oryxos.web.GlobalExceptionHandler;
import io.oryxos.web.provider.ProviderModelsService;
import java.util.Optional;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.RestClient;

/** providers 端点切片：CRUD 薄转发（冲突/非法→400、不存在→404，统一 ApiResponse）。 */
class ProviderApiControllerTest {

  private ProviderRegistry registry;
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    registry = mock(ProviderRegistry.class);
    mvc =
        MockMvcBuilders.standaloneSetup(
                new ProviderApiController(
                    registry, new ProviderModelsService(registry, RestClient.builder())))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
  }

  @Test
  @DisplayName("create 成功_返回 provider 视图（api-key 掩码回显，非明文）")
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
        .andExpect(jsonPath("$.data.apiKey").value("****")); // 掩码回显，明文不落 HTTP 响应
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
  @DisplayName("update 回传掩码 apiKey_视为未修改_保留原 key")
  void update_maskedKey_keepsOriginal() throws Exception {
    when(registry.find("kimi"))
        .thenReturn(
            Optional.of(
                new ProviderDef("kimi", "sk-secretvalue", "https://api.moonshot.cn", "月之暗面")));
    when(registry.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

    mvc.perform(
            put("/api/v1/providers/kimi")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"apiKey\":\"****alue\",\"baseUrl\":\"https://api.moonshot.cn\",\"description\":\"月之暗面\"}"))
        .andExpect(status().isOk());

    // 掩码（****alue，mask() 留末 4 位）被识别为未修改，落库的仍是原 key sk-secretvalue
    ArgumentCaptor<ProviderDef> captor = ArgumentCaptor.forClass(ProviderDef.class);
    verify(registry).save(captor.capture());
    Assertions.assertEquals("sk-secretvalue", captor.getValue().apiKey());
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

  @Test
  @DisplayName("models_mock_返回占位列表")
  void models_mock_returnsPlaceholder() throws Exception {
    when(registry.find("mock")).thenReturn(Optional.of(new ProviderDef("mock", null, null, null)));

    mvc.perform(get("/api/v1/providers/mock/models"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[0]").value("mock"));
  }

  @Test
  @DisplayName("models_不存在_返回404")
  void models_unknown_returns404() throws Exception {
    when(registry.find("ghost")).thenReturn(Optional.empty());

    mvc.perform(get("/api/v1/providers/ghost/models"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(404));
  }

  @Test
  @DisplayName("models_真实provider不可达_返回503")
  void models_unreachable_returns503() throws Exception {
    when(registry.find("down"))
        .thenReturn(Optional.of(new ProviderDef("down", "sk-x", "http://127.0.0.1:1", null)));

    mvc.perform(get("/api/v1/providers/down/models"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value(503));
  }
}
