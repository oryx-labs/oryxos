package io.oryxos.web.controller.dto;

import io.oryxos.core.notify.NotifyChannelDef;
import io.oryxos.core.secret.SensitiveConfigKeys;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 通知渠道视图（列表/详情返回）。
 *
 * <p>022：config 敏感项（{@link SensitiveConfigKeys} 名录）只回显掩码——口径与 {@link ProviderView#mask} 一致（确定性 +
 * 幂等），杜绝明文经查询接口泄露；掩码值被前端原样提交时由 controller 识别为"未修改"，保留原值。
 *
 * <p>webhook URL 本身也是凭证（拿到即可推送）：query 整体打码（钉钉 access_token / 企微 key 在 query）、 多段 path 末段打码（飞书 hook
 * id 在末段），scheme+host 与单段 path 保留以辨认端点。
 */
public record NotifyChannelView(
    String name, String type, String url, String description, Map<String, String> config) {

  /** 防御性拷贝：config 是可变 Map，出站前固化不可变（SpotBugs EI_EXPOSE_REP / EI_EXPOSE_REP2）。 */
  public NotifyChannelView {
    config = config == null ? Map.of() : Map.copyOf(config);
  }

  public static NotifyChannelView from(NotifyChannelDef d) {
    return new NotifyChannelView(
        d.name(),
        d.type(),
        CredentialMasks.maskWebhookUrl(d.url()),
        d.description(),
        maskConfig(d.config()));
  }

  /**
   * config 敏感项 → 确定性掩码（`****`+末 4 位，{@link ProviderView#mask} 同口径）；名录外原样。 controller
   * 的"未修改"判定复用本方法：提交值 == maskConfig(原值) 中对应项即视为未改。
   */
  public static Map<String, String> maskConfig(Map<String, String> config) {
    if (config == null || config.isEmpty()) {
      return Map.of();
    }
    Map<String, String> out = new LinkedHashMap<>();
    config.forEach(
        (key, value) ->
            out.put(key, SensitiveConfigKeys.isSensitive(key) ? ProviderView.mask(value) : value));
    return out;
  }
}
