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
import java.util.List;
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

  // —— 022 US3：敏感项掩码回显 + 未修改判定 ——

  private static NotifyChannelDef mailDef(String password) {
    return new NotifyChannelDef(
        "mail",
        "email",
        "smtp://placeholder",
        "d",
        java.util.Map.of(
            "host",
            "smtp.example.com",
            "port",
            "465",
            "from",
            "a@b.c",
            "to",
            "ops@b.c",
            "password",
            password));
  }

  @Test
  @DisplayName("022 查询回显_敏感项掩码_普通项原样_无明文")
  void query_masksSensitiveConfig() throws Exception {
    when(registry.list()).thenReturn(java.util.List.of(mailDef("p@ss-secret")));
    when(registry.find("mail")).thenReturn(Optional.of(mailDef("p@ss-secret")));

    mvc.perform(get("/api/v1/notify-channels"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[0].config.password").value("****cret"))
        .andExpect(jsonPath("$.data[0].config.host").value("smtp.example.com"));
    String detail =
        mvc.perform(get("/api/v1/notify-channels/mail"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    org.assertj.core.api.Assertions.assertThat(detail).doesNotContain("p@ss-secret");
  }

  @Test
  @DisplayName("022 更新_掩码原样提交=未修改_原值保留")
  void update_maskedValueKeepsOriginal() throws Exception {
    when(registry.exists("mail")).thenReturn(true);
    when(registry.find("mail")).thenReturn(Optional.of(mailDef("p@ss-secret")));
    when(registry.save(any())).thenAnswer(inv -> inv.getArgument(0));

    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                    "/api/v1/notify-channels/mail")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"type\":\"email\",\"url\":\"smtp://placeholder\",\"config\":{"
                        + "\"host\":\"smtp.example.com\",\"port\":\"465\",\"from\":\"a@b.c\","
                        + "\"to\":\"ops@b.c\",\"password\":\"****cret\"}}"))
        .andExpect(status().isOk());

    org.mockito.ArgumentCaptor<NotifyChannelDef> captor =
        org.mockito.ArgumentCaptor.forClass(NotifyChannelDef.class);
    verify(registry).save(captor.capture());
    org.assertj.core.api.Assertions.assertThat(captor.getValue().config())
        .containsEntry("password", "p@ss-secret"); // 掩码=未修改 → registry 收到原明文
  }

  @Test
  @DisplayName("022 更新_留空同样保留原值_新值则生效")
  void update_blankKeepsOriginal_newValueWins() throws Exception {
    when(registry.exists("mail")).thenReturn(true);
    when(registry.find("mail")).thenReturn(Optional.of(mailDef("p@ss-secret")));
    when(registry.save(any())).thenAnswer(inv -> inv.getArgument(0));

    // 留空 → 保留
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                    "/api/v1/notify-channels/mail")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"type\":\"email\",\"url\":\"smtp://placeholder\",\"config\":{"
                        + "\"host\":\"smtp.example.com\",\"port\":\"465\",\"from\":\"a@b.c\","
                        + "\"to\":\"ops@b.c\",\"password\":\"\"}}"))
        .andExpect(status().isOk());
    // 新值 → 生效
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                    "/api/v1/notify-channels/mail")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"type\":\"email\",\"url\":\"smtp://placeholder\",\"config\":{"
                        + "\"host\":\"smtp.example.com\",\"port\":\"465\",\"from\":\"a@b.c\","
                        + "\"to\":\"ops@b.c\",\"password\":\"new-pass-9\"}}"))
        .andExpect(status().isOk());

    org.mockito.ArgumentCaptor<NotifyChannelDef> captor =
        org.mockito.ArgumentCaptor.forClass(NotifyChannelDef.class);
    verify(registry, org.mockito.Mockito.times(2)).save(captor.capture());
    org.assertj.core.api.Assertions.assertThat(captor.getAllValues().get(0).config())
        .containsEntry("password", "p@ss-secret");
    org.assertj.core.api.Assertions.assertThat(captor.getAllValues().get(1).config())
        .containsEntry("password", "new-pass-9");
  }

  // —— webhook URL 掩码（URL 本身即凭证，拿到即可推送）——

  @Test
  @DisplayName("list 回显掩码_webhook URL 不明文泄露（access_token/key 在 query，hook id 在 path 末段）")
  void list_masksWebhookUrl() throws Exception {
    when(registry.list())
        .thenReturn(
            List.of(
                new NotifyChannelDef(
                    "dt",
                    "dingtalk",
                    "https://oapi.dingtalk.com/robot/send?access_token=abcd1234efgh",
                    null,
                    java.util.Map.of("host", "smtp.corp.com"))));

    mvc.perform(get("/api/v1/notify-channels"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[0].url").value("https://oapi.dingtalk.com/robot/****?****"));
  }

  @Test
  @DisplayName("update 回传掩码 url_视为未修改_保留原 webhook；非敏感字段照常更新")
  void update_maskedUrl_keepsOriginal() throws Exception {
    NotifyChannelDef existing =
        new NotifyChannelDef(
            "dt",
            "dingtalk",
            "https://oapi.dingtalk.com/robot/send?access_token=abcd1234efgh",
            "旧描述",
            java.util.Map.of());
    when(registry.find("dt")).thenReturn(Optional.of(existing));
    when(registry.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                    "/api/v1/notify-channels/dt")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"type\":\"dingtalk\","
                        + "\"url\":\"https://oapi.dingtalk.com/robot/****?****\","
                        + "\"description\":\"新描述\"}"))
        .andExpect(status().isOk());

    org.mockito.ArgumentCaptor<NotifyChannelDef> captor =
        org.mockito.ArgumentCaptor.forClass(NotifyChannelDef.class);
    verify(registry).save(captor.capture());
    NotifyChannelDef saved = captor.getValue();
    org.junit.jupiter.api.Assertions.assertEquals(
        "https://oapi.dingtalk.com/robot/send?access_token=abcd1234efgh",
        saved.url(),
        "掩码 url 不得覆盖真实 webhook");
    org.junit.jupiter.api.Assertions.assertEquals("新描述", saved.description(), "非敏感字段照常更新");
  }
}
