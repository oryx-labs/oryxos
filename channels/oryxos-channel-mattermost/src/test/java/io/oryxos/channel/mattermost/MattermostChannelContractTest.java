package io.oryxos.channel.mattermost;

import io.oryxos.core.channel.ChatKind;
import io.oryxos.core.channel.InboundAttachment;
import io.oryxos.core.channel.InboundMessage;
import io.oryxos.core.channel.InboundMessageServiceContractTestBase;
import java.util.List;

class MattermostChannelContractTest extends InboundMessageServiceContractTestBase {

  private final MattermostEventNormalizer normalizer =
      new MattermostEventNormalizer("contract-chan", "oryxbot");

  @Override
  protected String channelType() {
    return "mattermost";
  }

  @Override
  protected InboundMessage p2pMessage(String messageId, String content) {
    return normalizer
        .normalize(
            "token=t&user_id=u1&channel_id=c1&post_id="
                + messageId
                + "&channel_type=D&text="
                + content.replace(" ", "+"))
        .orElseThrow();
  }

  @Override
  protected InboundMessage groupMessage(String messageId, String content) {
    return normalizer
        .normalize(
            "token=t&user_id=u1&channel_id=g1&post_id="
                + messageId
                + "&channel_type=O&text=@oryxbot+"
                + content.replace(" ", "+"))
        .orElseThrow();
  }

  @Override
  protected InboundMessage nonTextualMessage(String messageId) {
    return normalizer
        .normalize(
            "token=t&user_id=u1&channel_id=c1&post_id=" + messageId + "&channel_type=D&text=")
        .orElseThrow();
  }

  @Override
  protected InboundMessage imageMessage(String messageId) {
    return new InboundMessage(
        channelType(),
        "contract-chan",
        messageId,
        ChatKind.P2P,
        "u1",
        "c1",
        "",
        false,
        false,
        List.of(InboundAttachment.imageUrl("https://example/img")));
  }
}
