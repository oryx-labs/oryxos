package io.oryxos.core.capability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.oryxos.core.profile.Profile;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CapabilityAssemblyTest {

  @Test
  @DisplayName("from refs partitions by kind")
  void partitions() {
    CapabilityAssembly a =
        CapabilityAssembly.from(
            List.of(
                CapabilityRef.tool("web_search"),
                CapabilityRef.skill("summarizer"),
                CapabilityRef.knowledge("ops")));
    assertTrue(a.constrainsTools());
    assertEquals(List.of("web_search"), a.tools());
    assertTrue(a.allowsSkill("summarizer"));
    assertFalse(a.allowsSkill("other"));
    assertTrue(a.allowsKnowledge("ops"));
  }

  @Test
  @DisplayName("Profile overlay replaces tools when constrained")
  void profileOverlay() {
    Profile base =
        new Profile(
            "writer",
            "d",
            null,
            new Profile.ProviderRef("deepseek", "m", null),
            List.of("web_search", "shell", "notify"),
            List.of("github"),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            Profile.Settings.defaults());
    Profile overlay =
        base.withCapabilityAssembly(
            CapabilityAssembly.from(List.of(CapabilityRef.tool("web_search"))));
    assertEquals(List.of("web_search"), overlay.tools());
    assertEquals(List.of("github"), overlay.mcpServers());
  }
}
