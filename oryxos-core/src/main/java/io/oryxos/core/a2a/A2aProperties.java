package io.oryxos.core.a2a;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code oryxos.a2a.*} — A2A discovery + message + outbound client + shared bearer + hop limit
 * (Direction C / I). Default off.
 */
@ConfigurationProperties(prefix = "oryxos.a2a")
public record A2aProperties(
    boolean enabled,
    @DefaultValue("OryxOS") String name,
    @DefaultValue("OryxOS Agent Harness — local agents advertised for A2A discovery")
        String description,
    @DefaultValue("http://localhost:8080") String publicBaseUrl,
    @DefaultValue("0.1.8-RELEASE") String version,
    @DefaultValue("0.3.0") String protocolVersion,
    /** Default local agent when params.metadata.agent is omitted. */
    @DefaultValue("") String defaultAgent,
    /**
     * Comma-separated hosts allowed for outbound A2A (SSRF gate). Empty = no remote calls. Example:
     * {@code peer.example,10.0.0.5,127.0.0.1}.
     */
    @DefaultValue("") String remoteHosts,
    @DefaultValue("30") int clientTimeoutSeconds,
    /**
     * Shared bearer for inbound {@code /api/v1/a2a} and outbound client. Empty = no A2A-specific
     * auth (platform API Key rules still apply when enabled).
     */
    @DefaultValue("") String sharedToken,
    /** Max {@code X-A2A-Hop} value accepted inbound / emitted outbound (anti loop). */
    @DefaultValue("3") int maxHops) {

  public A2aProperties {
    name = name == null || name.isBlank() ? "OryxOS" : name.strip();
    description =
        description == null || description.isBlank()
            ? "OryxOS Agent Harness — local agents advertised for A2A discovery"
            : description.strip();
    publicBaseUrl =
        publicBaseUrl == null || publicBaseUrl.isBlank()
            ? "http://localhost:8080"
            : publicBaseUrl.strip().replaceAll("/+$", "");
    version = version == null || version.isBlank() ? "0.1.8-RELEASE" : version.strip();
    protocolVersion =
        protocolVersion == null || protocolVersion.isBlank() ? "0.3.0" : protocolVersion.strip();
    defaultAgent = defaultAgent == null ? "" : defaultAgent.strip();
    remoteHosts = remoteHosts == null ? "" : remoteHosts.strip();
    if (clientTimeoutSeconds <= 0) {
      clientTimeoutSeconds = 30;
    }
    if (clientTimeoutSeconds > 300) {
      clientTimeoutSeconds = 300;
    }
    sharedToken = sharedToken == null ? "" : sharedToken.strip();
    if (maxHops <= 0) {
      maxHops = 3;
    }
    if (maxHops > 16) {
      maxHops = 16;
    }
  }

  public static A2aProperties disabled() {
    return new A2aProperties(
        false,
        "OryxOS",
        "OryxOS Agent Harness — local agents advertised for A2A discovery",
        "http://localhost:8080",
        "0.1.8-RELEASE",
        "0.3.0",
        "",
        "",
        30,
        "",
        3);
  }

  public String a2aServiceUrl() {
    return publicBaseUrl + "/api/v1/a2a";
  }

  public boolean hasSharedToken() {
    return !sharedToken.isBlank();
  }

  /** Host allow check for outbound A2A (case-insensitive exact host match). */
  public boolean isRemoteHostAllowed(String host) {
    if (host == null || host.isBlank() || remoteHosts.isBlank()) {
      return false;
    }
    String h = host.strip().toLowerCase(Locale.ROOT);
    Set<String> allowed =
        Arrays.stream(remoteHosts.split(","))
            .map(String::strip)
            .filter(s -> !s.isEmpty())
            .map(s -> s.toLowerCase(Locale.ROOT))
            .collect(Collectors.toSet());
    return allowed.contains(h);
  }

  /** Constant-time compare of presented bearer vs configured shared token. */
  public boolean matchesSharedToken(String presented) {
    if (!hasSharedToken() || presented == null || presented.isBlank()) {
      return false;
    }
    byte[] expected = sharedToken.getBytes(StandardCharsets.UTF_8);
    byte[] actual = presented.strip().getBytes(StandardCharsets.UTF_8);
    return MessageDigest.isEqual(expected, actual);
  }
}
