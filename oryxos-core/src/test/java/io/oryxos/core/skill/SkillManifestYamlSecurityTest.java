package io.oryxos.core.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class SkillManifestYamlSecurityTest {

  private final SkillManifestParser parser = new SkillManifestParser();

  @Test
  void keepsYaml11BooleanWordsAsStrings() {
    for (String word : new String[] {"yes", "no", "on", "off"}) {
      ParsedSkill skill =
          parser.parse(
              "---\nname: safe\ndescription: safe\nmetadata:\n  value: " + word + "\n---\nbody",
              true);
      assertEquals(word, skill.manifest().metadata().get("value"));
    }
  }

  @Test
  void rejectsCustomTagsDuplicateKeysAndAliases() {
    assertCode("name: safe\ndescription: !evil value");
    assertCode("name: safe\nname: other\ndescription: safe");
    assertCode("name: safe\ndescription: safe\nmetadata:\n  a: &x value\n  b: *x");
  }

  private void assertCode(String yaml) {
    SkillValidationException error =
        assertThrows(
            SkillValidationException.class,
            () -> parser.parse("---\n" + yaml + "\n---\nbody", true));
    assertEquals(SkillValidationCode.UNSAFE_YAML, error.code());
  }
}
