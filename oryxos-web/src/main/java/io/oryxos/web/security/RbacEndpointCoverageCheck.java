package io.oryxos.web.security;

import io.oryxos.web.config.WebRbacProperties;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/** RBAC 开启时枚举已注册 handler，未登记的受保护路径拒绝启动（039 SC-005）。关闭时不扫描。 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class RbacEndpointCoverageCheck implements ApplicationRunner {

  private static final Logger LOG = LoggerFactory.getLogger(RbacEndpointCoverageCheck.class);

  private static final String GAP_LIST = ", ";

  static final String MSG_UNMAPPED =
      "RBAC enabled but protected endpoints are not registered in RequestActionResolver"
          + " (fail-closed). Register them or skip them explicitly: ";

  private final WebRbacProperties rbacProperties;

  private final RequestMappingHandlerMapping handlerMapping;

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "rbac properties 与 handlerMapping 是 Spring 注入的共享单例，存同一引用正是意图。")
  public RbacEndpointCoverageCheck(
      WebRbacProperties rbacProperties,
      @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping handlerMapping) {
    this.rbacProperties = rbacProperties;
    this.handlerMapping = handlerMapping;
  }

  @Override
  public void run(ApplicationArguments args) {
    if (!rbacProperties.isEnabled()) {
      LOG.debug("RBAC disabled, endpoint coverage scan skipped");
      return;
    }
    List<String> gaps =
        RbacEndpointCoverage.gaps(
            RbacEndpointCoverage.fromHandlers(handlerMapping.getHandlerMethods().entrySet()));
    if (gaps.isEmpty()) {
      return;
    }
    throw new IllegalStateException(MSG_UNMAPPED + String.join(GAP_LIST, gaps));
  }
}
