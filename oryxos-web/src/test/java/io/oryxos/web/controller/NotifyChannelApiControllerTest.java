package io.oryxos.web.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.oryxos.core.notify.NotifyChannelDef;
import io.oryxos.core.notify.NotifyChannelRegistry;
import io.oryxos.web.GlobalExceptionHandler;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** notify-channels 端点切片：CRUD 薄转发（冲突/非法→400、不存在→404，统一 ApiResponse）。 */
class NotifyChannelApiControllerTest {

  private NotifyChannelRegistry registry;
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    registry = mock(NotifyChannelRegistry.class);
    mvc =
        MockMvcBuilders.standaloneSetup(new NotifyChannelApiController(registry))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
  }

  @Test
  @DisplayName("create 成功_返回渠道视图")
  void create_success_returnsView() throws Exception {
    when(registry.exists("team-lark")).thenReturn(false);
    when(registry.save(any()))
        .thenReturn(new NotifyChannelDef("team-lark", "feishu", "https://x/hook", "团队群"));

    mvc.perform(
            post("/api/v1/notify-channels")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"name\":\"team-lark\",\"type\":\"feishu\",\"url\":\"https://x/hook\",\"description\":\"团队群\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.name").value("team-lark"))
        .andExpect(jsonPath("$.data.type").value("feishu"));
  }

  @Test
  @DisplayName("create 名字冲突_返回400_不落库")
  void create_conflict_returns400() throws Exception {
    when(registry.exists("dup")).thenReturn(true);

    mvc.perform(
            post("/api/v1/notify-channels")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"dup\",\"type\":\"feishu\",\"url\":\"https://x/hook\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400));
    verify(registry, never()).save(any());
  }

  @Test
  @DisplayName("create 非法类型_返回400")
  void create_invalidType_returns400() throws Exception {
    when(registry.exists("x")).thenReturn(false);

    mvc.perform(
            post("/api/v1/notify-channels")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"x\",\"type\":\"telegram\",\"url\":\"https://x/hook\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400));
    verify(registry, never()).save(any());
  }

  @Test
  @DisplayName("get 不存在_返回404")
  void get_unknown_returns404() throws Exception {
    when(registry.find("ghost")).thenReturn(Optional.empty());

    mvc.perform(get("/api/v1/notify-channels/ghost"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(404));
  }

  @Test
  @DisplayName("delete 不存在_返回404_不触发删除")
  void delete_unknown_returns404() throws Exception {
    when(registry.exists("ghost")).thenReturn(false);

    mvc.perform(delete("/api/v1/notify-channels/ghost")).andExpect(status().isNotFound());
    verify(registry, never()).delete(eq("ghost"));
  }
}
