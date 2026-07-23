package io.oryxos.web.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.oryxos.core.skill.SkillLoader;
import io.oryxos.core.skill.SkillRegistry;
import io.oryxos.core.skill.SkillService;
import io.oryxos.core.skill.SkillStore;
import io.oryxos.web.GlobalExceptionHandler;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 第 32 节验收 harness：SkillApiController——全局 Skill 库 CRUD（冲突→400、不存在→404）。 */
class SkillApiControllerTest {

  @TempDir Path oryxosRoot;
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    SkillStore store = new SkillStore(oryxosRoot);
    SkillLoader loader = new SkillLoader(oryxosRoot.resolve("skills"));
    SkillRegistry registry = loader.loadAll();
    SkillService service = new SkillService(store, registry, loader);
    mvc =
        MockMvcBuilders.standaloneSetup(new SkillApiController(service))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
  }

  @Test
  @DisplayName("create → list/get 能取到")
  void create_thenListAndGet() throws Exception {
    mvc.perform(
            post("/api/v1/skills")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"name\":\"report-format\",\"description\":\"研报格式\",\"body\":\"# 规范\\n带出处\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0))
        .andExpect(jsonPath("$.data.name").value("report-format"));

    mvc.perform(get("/api/v1/skills"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[0].name").value("report-format"));

    mvc.perform(get("/api/v1/skills/report-format"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.description").value("研报格式"));
  }

  @Test
  @DisplayName("create 同名 → 400")
  void create_duplicate_returns400() throws Exception {
    mvc.perform(
            post("/api/v1/skills")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"dup\",\"description\":\"d\",\"body\":\"b\"}"))
        .andExpect(status().isOk());
    mvc.perform(
            post("/api/v1/skills")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"dup\",\"description\":\"d2\",\"body\":\"b2\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400));
  }

  @Test
  @DisplayName("create 空 name → 400")
  void create_blankName_returns400() throws Exception {
    mvc.perform(
            post("/api/v1/skills")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"\",\"description\":\"d\",\"body\":\"b\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  @DisplayName("get / update / delete 不存在 → 404")
  void missing_returns404() throws Exception {
    mvc.perform(get("/api/v1/skills/nope")).andExpect(status().isNotFound());
    mvc.perform(
            put("/api/v1/skills/nope")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"description\":\"d\",\"body\":\"b\"}"))
        .andExpect(status().isNotFound());
    mvc.perform(delete("/api/v1/skills/nope")).andExpect(status().isNotFound());
  }

  @Test
  @DisplayName("import 空 url → 400；非 http/https → 400")
  void import_invalidUrl_returns400() throws Exception {
    mvc.perform(
            post("/api/v1/skills/import")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"url\":\"\"}"))
        .andExpect(status().isBadRequest());
    mvc.perform(
            post("/api/v1/skills/import")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"url\":\"file:///etc/passwd\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400));
  }

  @Test
  @DisplayName("import 指向回环/内网/云元数据地址 → 400（SSRF 防护）")
  void import_ssrf_blocked() throws Exception {
    for (String url :
        new String[] {
          "http://127.0.0.1:8080/x/SKILL.md",
          "http://localhost/x/SKILL.md",
          "http://169.254.169.254/latest/meta-data/",
          "http://10.0.0.5/SKILL.md"
        }) {
      mvc.perform(
              post("/api/v1/skills/import")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"url\":\"" + url + "\"}"))
          .andExpect(status().isBadRequest());
    }
  }

  @Test
  @DisplayName("update 覆写；delete 移除")
  void update_thenDelete() throws Exception {
    mvc.perform(
            post("/api/v1/skills")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"s1\",\"description\":\"old\",\"body\":\"old\"}"))
        .andExpect(status().isOk());
    mvc.perform(
            put("/api/v1/skills/s1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"description\":\"new\",\"body\":\"new body\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.description").value("new"));
    mvc.perform(delete("/api/v1/skills/s1")).andExpect(status().isOk());
    mvc.perform(get("/api/v1/skills/s1")).andExpect(status().isNotFound());
  }
}
