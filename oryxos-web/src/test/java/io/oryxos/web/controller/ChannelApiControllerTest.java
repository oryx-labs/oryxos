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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.oryxos.core.channel.ChannelAdminService;
import io.oryxos.core.channel.ChannelConfig;
import io.oryxos.core.channel.ChannelConfigLoader;
import io.oryxos.core.channel.ChannelStatus;
import io.oryxos.core.policy.AssetAwareAuthorizationServiceImpl;
import io.oryxos.core.policy.AssetGovernance;
import io.oryxos.core.policy.AssetGovernanceStore;
import io.oryxos.core.policy.AuthorizationService;
import io.oryxos.web.GlobalExceptionHandler;
import io.oryxos.web.security.AssetBindGuard;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 017 T020：channels 端点切片——CRUD 薄转发、凭证掩码不泄密、点名错误文案（400/404）。 */
class ChannelApiControllerTest {

  private ChannelAdminService admin;
  private ChannelApiController controller;
  private MockMvc mvc;

  @TempDir Path governanceRoot;

  private static final String CHANNEL = "ops-feishu";

  private static final String CHANNELS_YAML = "channels.yaml";

  private static final ChannelConfig RAW =
      new ChannelConfig(
          "ops-feishu", "feishu", "${FEISHU_APP_ID}", "${FEISHU_APP_SECRET}", "ops-agent", true);

  @BeforeEach
  void setUp() {
    admin = mock(ChannelAdminService.class);
    controller = new ChannelApiController(admin);
    mvc =
        MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
  }

  @Test
  @DisplayName("list：${} 占位原样回显；明文凭证掩码为 ******")
  void listMasksPlaintextSecret() throws Exception {
    ChannelConfig plaintext =
        new ChannelConfig("raw-chan", "feishu", "cli_x", "real-secret", "ops-agent", true);
    when(admin.listRaw()).thenReturn(List.of(RAW, plaintext));

    mvc.perform(get("/api/v1/channels"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[0].appSecret").value("${FEISHU_APP_SECRET}"))
        .andExpect(jsonPath("$.data[1].appSecret").value("******"));
  }

  @Test
  @DisplayName("status：呈现渠道在线状态与点名错误原因（FR-014/SC-008）")
  void statusShowsStateAndError() throws Exception {
    when(admin.status())
        .thenReturn(
            List.of(
                ChannelStatus.ok("ok-chan", "feishu", "ops-agent", ChannelStatus.State.CONNECTED),
                ChannelStatus.error(
                    "bad-chan", "feishu", "ghost", "渠道 bad-chan 绑定的 Agent ghost 不存在")));

    mvc.perform(get("/api/v1/channels/status"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[0].state").value("CONNECTED"))
        .andExpect(jsonPath("$.data[1].state").value("ERROR"))
        .andExpect(jsonPath("$.data[1].error").value("渠道 bad-chan 绑定的 Agent ghost 不存在"));
  }

  @Test
  @DisplayName("add：落盘 + 立即上线；回显掩码")
  void addSuccess() throws Exception {
    when(admin.add(any())).thenReturn(RAW);

    mvc.perform(
            post("/api/v1/channels")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"name\":\"ops-feishu\",\"type\":\"feishu\",\"appId\":\"${FEISHU_APP_ID}\","
                        + "\"appSecret\":\"${FEISHU_APP_SECRET}\",\"agent\":\"ops-agent\",\"enabled\":true}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.name").value("ops-feishu"))
        .andExpect(jsonPath("$.data.appSecret").value("${FEISHU_APP_SECRET}"));
  }

  @Test
  @DisplayName("add 校验失败（Agent 不存在）：400 + 点名文案")
  void addValidationFailure() throws Exception {
    when(admin.add(any()))
        .thenThrow(new IllegalArgumentException("渠道 ops-feishu 绑定的 Agent ghost 不存在"));

    mvc.perform(
            post("/api/v1/channels")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"name\":\"ops-feishu\",\"type\":\"feishu\",\"appId\":\"a\","
                        + "\"appSecret\":\"b\",\"agent\":\"ghost\",\"enabled\":true}"))
        .andExpect(status().is4xxClientError());
  }

  @Test
  @DisplayName("update 不存在的渠道：404，不触达 admin.update")
  void updateMissingReturns404() throws Exception {
    when(admin.listRaw()).thenReturn(List.of());

    mvc.perform(
            put("/api/v1/channels/ghost-chan")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"name\":\"ghost-chan\",\"type\":\"feishu\",\"appId\":\"a\","
                        + "\"appSecret\":\"b\",\"agent\":\"x\",\"enabled\":true}"))
        .andExpect(status().isNotFound());
    verify(admin, never()).update(eq("ghost-chan"), any());
  }

  @Test
  @DisplayName("list：extra 占位原样回显；明文 extra 掩码")
  void listMasksExtraSecrets() throws Exception {
    ChannelConfig withExtra =
        new ChannelConfig(
            "ops-teams",
            "teams",
            "${TEAMS_APP_ID}",
            "${TEAMS_APP_SECRET}",
            "ops-agent",
            true,
            java.util.Map.of("tenant_id", "${TEAMS_TENANT_ID}", "plain", "secret-value"));
    when(admin.listRaw()).thenReturn(List.of(withExtra));

    mvc.perform(get("/api/v1/channels"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[0].extra.tenant_id").value("${TEAMS_TENANT_ID}"))
        .andExpect(jsonPath("$.data[0].extra.plain").value("******"));
  }

  @Test
  @DisplayName("delete：存在则断开并移除；不存在 404")
  void deleteFlow() throws Exception {
    when(admin.listRaw()).thenReturn(List.of(RAW));
    mvc.perform(delete("/api/v1/channels/ops-feishu")).andExpect(status().isOk());
    verify(admin).remove("ops-feishu");

    when(admin.listRaw()).thenReturn(List.of());
    mvc.perform(delete("/api/v1/channels/ops-feishu")).andExpect(status().isNotFound());
  }

  @Test
  @DisplayName("flag 开：OFFLINE 渠道 update 被 AuthorizationService 拒绝且不落盘")
  void updateDeniedWhenChannelOffline() throws Exception {
    AssetGovernance offline =
        new AssetGovernance(
            "alice", "1", AssetGovernance.Visibility.PRIVATE, null, AssetGovernance.Health.OFFLINE);
    new ChannelConfigLoader(governanceRoot.resolve(CHANNELS_YAML))
        .save(
            List.of(
                new ChannelConfig(CHANNEL, "feishu", "a", "b", "ops-agent", true)
                    .withGovernance(offline)));
    AuthorizationService auth =
        new AssetAwareAuthorizationServiceImpl(
            AuthorizationService.ALLOW_ALL, new AssetGovernanceStore(governanceRoot), true);
    controller.setAssetBindGuard(new AssetBindGuard(auth));
    when(admin.listRaw()).thenReturn(List.of(RAW));

    mvc.perform(
            put("/api/v1/channels/" + CHANNEL)
                .contentType(MediaType.APPLICATION_JSON)
                .content(updateBody()))
        .andExpect(status().isForbidden());
    verify(admin, never()).update(eq(CHANNEL), any());
  }

  @Test
  @DisplayName("flag 关：同一 OFFLINE 块不额外拒绝 update")
  void updateNotExtraDeniedWhenFlagOff() throws Exception {
    AssetGovernance offline =
        new AssetGovernance(
            "alice", "1", AssetGovernance.Visibility.PRIVATE, null, AssetGovernance.Health.OFFLINE);
    new ChannelConfigLoader(governanceRoot.resolve(CHANNELS_YAML))
        .save(
            List.of(
                new ChannelConfig(CHANNEL, "feishu", "a", "b", "ops-agent", true)
                    .withGovernance(offline)));
    AuthorizationService auth =
        new AssetAwareAuthorizationServiceImpl(
            AuthorizationService.ALLOW_ALL, new AssetGovernanceStore(governanceRoot), false);
    controller.setAssetBindGuard(new AssetBindGuard(auth));
    when(admin.listRaw()).thenReturn(List.of(RAW));
    when(admin.update(eq(CHANNEL), any())).thenReturn(RAW);

    mvc.perform(
            put("/api/v1/channels/" + CHANNEL)
                .contentType(MediaType.APPLICATION_JSON)
                .content(updateBody()))
        .andExpect(status().isOk());
    verify(admin).update(eq(CHANNEL), any());
  }

  private static String updateBody() {
    return "{\"name\":\"ops-feishu\",\"type\":\"feishu\",\"appId\":\"a\","
        + "\"appSecret\":\"b\",\"agent\":\"ops-agent\",\"enabled\":true}";
  }
}
