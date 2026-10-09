package io.oryxos.core.channel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MentionStrippingTest {

  private static final String BOT = "rchuangbot";

  @Test
  @DisplayName("只剥离指向本机器人的那段，邮箱与他人提及原样保留")
  void keepsEmailsAndOtherMentions() {
    String text = "@rchuangbot 请把报价单发到 alice@gmail.com 和 @bob 一起看";
    assertEquals(
        "请把报价单发到 alice@gmail.com 和 @bob 一起看",
        MentionStripping.strip(text, MentionStripping.atMention(BOT)));
  }

  @Test
  @DisplayName("邮箱里的 @bot 不是提及")
  void emailLocalPartIsNotAMention() {
    String text = "ops@rchuangbot.example.com 加进群";
    assertFalse(MentionStripping.atMention(BOT).matcher(text).find());
    assertEquals(text, MentionStripping.strip(text, MentionStripping.atMention(BOT)));
  }

  @Test
  @DisplayName("更长的用户名不会被 @bot 前缀命中")
  void longerUsernameIsNotAMatch() {
    assertFalse(MentionStripping.atMention(BOT).matcher("@rchuangbot2 你好").find());
  }

  @Test
  @DisplayName("大小写不敏感，且命中自身的判定与剥离用同一模式")
  void isCaseInsensitive() {
    assertTrue(MentionStripping.atMention(BOT).matcher("请问 @RchuangBot 在吗").find());
    assertEquals(
        "请问 在吗", MentionStripping.strip("请问 @RchuangBot 在吗", MentionStripping.atMention(BOT)));
  }

  @Test
  @DisplayName("角括号形式只命中给本应用的那一段")
  void angleMentionTargetsOneId() {
    String text = "<@111> 请问 <@222> 有空吗";
    assertEquals(
        "请问 <@222> 有空吗", MentionStripping.strip(text, MentionStripping.angleMention("111")));
    assertEquals(
        "<@111> 请问 有空吗", MentionStripping.strip(text, MentionStripping.angleMention("222")));
    assertEquals(
        "<@111> 请问 <@222> 有空吗", MentionStripping.strip(text, MentionStripping.angleMention("333")));
  }

  @Test
  @DisplayName("空用户名/空 id 得到永不匹配的模式")
  void blankIdentityNeverMatches() {
    assertFalse(MentionStripping.atMention("").matcher("@anything").find());
    assertFalse(MentionStripping.atMention(null).matcher("@anything").find());
    assertFalse(MentionStripping.angleMention("  ").matcher("<@1>").find());
  }

  @Test
  @DisplayName("null 与空白文本得到空串")
  void blankTextYieldsEmpty() {
    assertEquals("", MentionStripping.strip(null, MentionStripping.atMention(BOT)));
    assertEquals("", MentionStripping.strip("   ", MentionStripping.atMention(BOT)));
  }
}
