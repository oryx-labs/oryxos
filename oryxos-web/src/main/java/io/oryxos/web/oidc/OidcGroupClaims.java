package io.oryxos.web.oidc;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** 把 id_token 里的组声明收成去空字符串列表。不是授权裁决。 */
final class OidcGroupClaims {

  private static final String CLAIM_ITEM_DELIMITER = ",";

  private OidcGroupClaims() {}

  /** null、空串、非集合且非字符串均视为无组。逗号分隔的单个字符串会拆开；不按空格拆，组名可以含空格。 */
  static List<String> normalize(Object raw) {
    if (raw == null) {
      return List.of();
    }
    if (raw instanceof String text) {
      return splitText(text);
    }
    if (raw instanceof Collection<?> values) {
      return fromCollection(values);
    }
    return List.of();
  }

  private static List<String> splitText(String text) {
    if (text.isBlank()) {
      return List.of();
    }
    if (!text.contains(CLAIM_ITEM_DELIMITER)) {
      return List.of(text.strip());
    }
    List<String> groups = new ArrayList<>();
    for (String part : text.split(CLAIM_ITEM_DELIMITER)) {
      addIfPresent(groups, part);
    }
    return List.copyOf(groups);
  }

  private static List<String> fromCollection(Collection<?> values) {
    List<String> groups = new ArrayList<>();
    for (Object value : values) {
      if (value != null) {
        addIfPresent(groups, value.toString());
      }
    }
    return List.copyOf(groups);
  }

  private static void addIfPresent(List<String> groups, String raw) {
    if (raw != null && !raw.isBlank()) {
      groups.add(raw.strip());
    }
  }
}
