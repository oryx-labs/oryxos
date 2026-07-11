package io.oryxos.tool.sandbox;

/** 涉外动作类型：文件读 / 文件写 / Shell 命令 / HTTP 请求——各对应一份白名单（24 节）。 */
public enum ActionType {
  FILE_READ,
  FILE_WRITE,
  SHELL_COMMAND,
  HTTP_REQUEST
}
