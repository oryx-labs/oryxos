package io.oryxos.web.controller;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.oryxos.core.a2a.A2aAgentCard;
import io.oryxos.core.a2a.A2aAgentCardService;
import java.util.Objects;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A2A Agent Card discovery (RFC 8615 well-known). Returns bare JSON (not ApiResponse wrapper) so
 * A2A clients can parse the card directly.
 */
@SuppressFBWarnings(
    value = {"SPRING_ENDPOINT", "EI_EXPOSE_REP2"},
    justification = "Spring MVC injects card service; discovery endpoint is intentionally public.")
@RestController
// 与它所依赖的 bean 用【同一个】条件。
// @ConditionalOnBean 在这里永远为 false：组件扫描早于配置类处理，
// 求值时那个 bean 的定义还没注册（实测 0.1.6-RELEASE，四个端点全 404）。
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
    prefix = "oryxos.a2a",
    name = "enabled",
    havingValue = "true")
public class A2aAgentCardController {

  private final A2aAgentCardService cardService;

  public A2aAgentCardController(A2aAgentCardService cardService) {
    this.cardService = Objects.requireNonNull(cardService, "cardService");
  }

  @GetMapping(path = "/.well-known/agent-card.json", produces = MediaType.APPLICATION_JSON_VALUE)
  public A2aAgentCard agentCard() {
    return cardService.card();
  }
}
