package io.oryxos.web.security;

import io.oryxos.storage.WebUserService;
import io.oryxos.web.config.WebAuthProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Component;

/**
 * 启动校验（012-web-auth，FR-006）：{@code auth.enabled=true} 但无 enabled 账号时阻断启动， 不静默裸奔。提示先跑 {@code oryxos
 * user add}。
 *
 * <p>用 {@link ApplicationRunner}（context 就绪后跑，比 @Bean init 可靠）。放 oryxos-web 与 filter 同模块。
 *
 * <p>只在 SERVLET web 模式（serve/gateway）跑——{@code oryxos user add} 等管理命令用 {@code
 * WebApplicationType.NONE}（不起 web server），装上它会死循环（user add 自身启动被无账号阻断，永远建不了首账号）。 {@link
 * ConditionalOnWebApplication} 限定只 SERVLET 模式装配，CLI 管理命令不受影响。
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class AuthStartupCheck implements ApplicationRunner {

  private static final Logger LOG = LoggerFactory.getLogger(AuthStartupCheck.class);

  private final WebAuthProperties properties;
  private final WebUserService userService;

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = {"EI_EXPOSE_REP2"},
      justification =
          "properties/userService 均为 Spring 注入的共享单例，构造注入存同一引用正是意图"
              + "（镜像既有 Controller 的 SuppressFBWarnings 模式）。")
  public AuthStartupCheck(WebAuthProperties properties, WebUserService userService) {
    this.properties = properties;
    this.userService = userService;
  }

  @Override
  public void run(ApplicationArguments args) {
    if (properties.isEnabled() && !userService.hasEnabledAccount()) {
      String msg =
          "Web auth enabled (oryxos.web.auth.enabled=true) but no enabled account found. "
              + "Run 'oryxos user add <username>' before starting serve.";
      LOG.error(msg);
      throw new IllegalStateException(msg);
    }
    LOG.debug("Auth startup check passed (enabled={}, accounts present)", properties.isEnabled());
  }
}
