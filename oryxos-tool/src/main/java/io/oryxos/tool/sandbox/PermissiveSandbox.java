package io.oryxos.tool.sandbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 24 节 WhitelistSandbox 就位前的临时装配：放行一切，但每次放行都记 WARN 留痕—— 绝不静默裸奔，日志里看得见"这套环境还没开白名单"。生产不可用，Demo 验证专用。
 */
public class PermissiveSandbox implements Sandbox {

  private static final Logger LOG = LoggerFactory.getLogger(PermissiveSandbox.class);

  @Override
  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "CRLF_INJECTION_LOGS",
      justification = "target 已在调用点以 char 形态 replace 链消毒；taint 分析误报")
  public void enforce(SandboxAction action) {
    // 消毒内联：FindSecBugs 只认调用点可达的 char 形态 replace 链（16 节同款教训）
    String target = action.target() == null ? "" : action.target();
    LOG.warn(
        "Sandbox 白名单未启用（24 节接入），放行 {} -> {}",
        action.type(),
        target.replace('\r', '_').replace('\n', '_'));
  }
}
