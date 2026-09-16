package io.oryxos.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 资产治理变更落库（041）。写失败只记 ERROR，不把已写成功的侧车回滚成失败——审计是旁路，权威仍是 GOVERNANCE.yml。 */
public class AssetGovernanceEventRecorder {

  private static final Logger LOG = LoggerFactory.getLogger(AssetGovernanceEventRecorder.class);

  private static final int MAX_ACTOR = 128;
  private static final int MAX_ID = 255;
  private static final int MAX_SUMMARY = 512;
  private static final int MAX_TYPE = 32;

  private static final String UNKNOWN_ACTOR = "anonymous";

  private final AssetGovernanceEventRepository repository;

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "repository 为 Spring 注入共享单例，存同一引用正是意图。")
  public AssetGovernanceEventRecorder(AssetGovernanceEventRepository repository) {
    this.repository = repository;
  }

  /** 记录一次侧车变更；失败吞掉并记 ERROR。 */
  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "CRLF_INJECTION_LOGS",
      justification = "exception toString 仅诊断；审计写失败不得回滚已落盘的侧车；摘要不含凭证明文。")
  public void record(String actor, String resourceType, String resourceId, String changeSummary) {
    try {
      AssetGovernanceEvent event = new AssetGovernanceEvent();
      event.setActor(truncate(actor == null || actor.isBlank() ? UNKNOWN_ACTOR : actor, MAX_ACTOR));
      event.setResourceType(truncate(resourceType == null ? "unknown" : resourceType, MAX_TYPE));
      event.setResourceId(truncate(resourceId == null ? "" : resourceId, MAX_ID));
      event.setChangeSummary(
          truncate(changeSummary == null ? "unspecified" : changeSummary, MAX_SUMMARY));
      repository.save(event);
    } catch (RuntimeException ex) {
      LOG.error("asset_governance_events 写入失败（侧车不回滚）：{}", ex.toString());
    }
  }

  private static String truncate(String value, int max) {
    if (value == null) {
      return null;
    }
    return value.length() <= max ? value : value.substring(0, max);
  }
}
