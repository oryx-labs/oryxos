package io.oryxos.storage;

/** 认证审计事件类型（040）。落库存 {@link #name()}。 */
public enum AuthEventType {
  LOGIN_SUCCESS,
  LOGIN_FAILURE,
  LOGOUT,
  MAPPING_UPSERT,
  MAPPING_DELETE
}
