package io.oryxos.web;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.oryxos.core.OryxTool;
import io.oryxos.core.memory.MemoryService;
import io.oryxos.core.profile.Profile;
import io.oryxos.core.profile.ProfileRegistry;
import io.oryxos.web.controller.MemoryApiController;
import io.oryxos.web.controller.ProfileApiController;
import io.oryxos.web.controller.SystemApiController;
import io.oryxos.web.controller.ToolApiController;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 课件《第26节》验收 harness：WebSmokeIT——只读端点（health/info/profiles/tools/memory）冒烟：路由通、 统一 ApiResponse
 * 信封、真实渲染。
 *
 * <p>说明：真正"起完整上下文验 JPA 仓库扫描 &gt;0"的整机冒烟依赖 oryxos-boot 装配（OryxOsRuntime 在 cli）， 不在只依赖 core 的 web
 * 模块内；那部分由手动 `serve`（人工项）与整包冒烟覆盖。本 IT 用 standalone 覆盖 web 层。
 */
@Tag("integration")
class WebSmokeIT {

  private MockMvc mvc;

  private static Profile profile(String name) {
    return new Profile(
        name,
        "运维助手",
        null,
        new Profile.ProviderRef("deepseek", "deepseek-chat", null),
        List.of("http_get"),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        Profile.Settings.defaults());
  }

  @BeforeEach
  void setUp() {
    ProfileRegistry registry = new ProfileRegistry(Map.of("ops", profile("ops")));
    MemoryService memory = mock(MemoryService.class);
    when(memory.readAll()).thenReturn("## 核心记忆\n用户偏好 AI");
    OryxTool tool = mock(OryxTool.class);
    when(tool.getName()).thenReturn("http_get");
    when(tool.getDescription()).thenReturn("GET 请求");

    mvc =
        MockMvcBuilders.standaloneSetup(
                new SystemApiController(registry),
                new ProfileApiController(registry),
                new MemoryApiController(memory),
                new ToolApiController(Map.of("http_get", tool)))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
  }

  @Test
  @DisplayName("health/info/profiles/tools/memory 只读端点可达且统一信封")
  void readEndpointsReachable() throws Exception {
    mvc.perform(MockMvcRequestBuilders.get("/api/v1/health"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0))
        .andExpect(jsonPath("$.data.status").value("ok"));

    mvc.perform(MockMvcRequestBuilders.get("/api/v1/info").accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.providers[0]").value("deepseek"));

    mvc.perform(MockMvcRequestBuilders.get("/api/v1/profiles"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[0].name").value("ops"));

    mvc.perform(MockMvcRequestBuilders.get("/api/v1/tools"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[0].name").value("http_get"));

    mvc.perform(MockMvcRequestBuilders.get("/api/v1/memory"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data").value("## 核心记忆\n用户偏好 AI"));
  }
}
