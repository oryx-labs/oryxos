package io.oryxos.storage;

/** 认证审计事件类型（040）。落库存 {@link #name()}。 */
public enum AuthEventType {
  LOGIN_SUCCESS,
  LOGIN_FAILURE,
  LOGOUT,
  MAPPING_UPSERT,
  MAPPING_DELETE,
  /** 登录时按已配置的 IdP 组写入本地角色；不是授权拒绝事件。 */
  GROUP_ROLE_SYNC
}
