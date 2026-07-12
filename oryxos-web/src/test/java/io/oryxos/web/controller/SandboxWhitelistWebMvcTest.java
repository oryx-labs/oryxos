package io.oryxos.web.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.oryxos.core.sandbox.SandboxWhitelist;
import io.oryxos.core.sandbox.SandboxWhitelist.Category;
import io.oryxos.web.GlobalExceptionHandler;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * HTTP 层测试（standalone MockMvc）：验证三个端点的路由、动词映射、请求体/查询参数绑定与 400 翻译 （{@link
 * GlobalExceptionHandler}）真正生效——补 {@link SandboxWhitelistControllerTest} 只测控制器逻辑之不足。 oryxos-web
 * 无 @SpringBootApplication，故用 standaloneSetup 而非 @WebMvcTest。
 */
class SandboxWhitelistWebMvcTest {

  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    mvc =
        MockMvcBuilders.standaloneSetup(new SandboxWhitelistController(new InMemoryWhitelist()))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
  }

  /** 内存替身：够测 HTTP 契约，不牵扯 tool 实现。 */
  static final class InMemoryWhitelist implements SandboxWhitelist {
    private final Map<Category, List<String>> store = new EnumMap<>(Category.class);

    InMemoryWhitelist() {
      for (Category category : Category.values()) {
        store.put(category, new ArrayList<>());
      }
    }

    @Override
    public List<String> list(Category category) {
      return List.copyOf(store.get(category));
    }

    @Override
    public boolean add(Category category, String value) {
      List<String> entries = store.get(category);
      if (entries.contains(value)) {
        return false;
      }
      entries.add(value);
      return true;
    }

    @Override
    public boolean remove(Category category, String value) {
      return store.get(category).remove(value);
    }
  }

  @Test
  @DisplayName("GET 返回三类白名单_200")
  void getReturnsAllCategories() throws Exception {
    mvc.perform(get("/api/v1/sandbox/whitelist"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0))
        .andExpect(jsonPath("$.data.file").isArray())
        .andExpect(jsonPath("$.data.shell").isArray())
        .andExpect(jsonPath("$.data.http").isArray());
  }

  @Test
  @DisplayName("POST 增加域名_200 且回显条目")
  void postAddsDomain() throws Exception {
    mvc.perform(
            post("/api/v1/sandbox/whitelist/http")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"value\":\"*.example.com\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.changed").value(true))
        .andExpect(jsonPath("$.data.entries[0]").value("*.example.com"));
  }

  @Test
  @DisplayName("DELETE 按 value 查询参数删除_200")
  void deleteRemovesByQueryParam() throws Exception {
    mvc.perform(
            post("/api/v1/sandbox/whitelist/shell")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"value\":\"ls\"}"))
        .andExpect(status().isOk());

    mvc.perform(delete("/api/v1/sandbox/whitelist/shell").param("value", "ls"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.changed").value(true))
        .andExpect(jsonPath("$.data.entries").isEmpty());
  }

  @Test
  @DisplayName("未知类别_400")
  void unknownCategoryReturns400() throws Exception {
    mvc.perform(
            post("/api/v1/sandbox/whitelist/dns")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"value\":\"x\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400));
  }

  @Test
  @DisplayName("空值增加_400")
  void blankValueReturns400() throws Exception {
    mvc.perform(
            post("/api/v1/sandbox/whitelist/http")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"value\":\"\"}"))
        .andExpect(status().isBadRequest());
  }
}
