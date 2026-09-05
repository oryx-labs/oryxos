package io.oryxos.core.channel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DefaultInboundMediaEnricherTest {

  private final DefaultInboundMediaEnricher enricher = new DefaultInboundMediaEnricher();

  @Test
  @DisplayName("文本消息原样传递")
  void textOnly() {
    InboundMessage msg =
        new InboundMessage(
            "feishu", "ops-feishu", "m1", ChatKind.P2P, "u1", "c1", "你好", true, false, List.of());
    assertEquals("你好", enricher.toAgentInput(msg));
  }

  @Test
  @DisplayName("图片 URL 附件转为 Agent 可消费说明")
  void imageUrlAttachment() {
    InboundMessage msg =
        new InboundMessage(
            "wecom",
            "ops-wecom",
            "m2",
            ChatKind.P2P,
            "u1",
            "c1",
            "",
            false,
            false,
            List.of(InboundAttachment.imageUrl("https://example/img.png")));
    String input = enricher.toAgentInput(msg);
    assertTrue(input.contains("https://example/img.png"));
    assertTrue(input.contains("图片"));
  }

  @Test
  @DisplayName("飞书 image_key 作为资源引用传递")
  void imageReferenceAttachment() {
    InboundMessage msg =
        new InboundMessage(
            "feishu",
            "ops-feishu",
            "m3",
            ChatKind.P2P,
            "u1",
            "c1",
            "",
            false,
            false,
            List.of(InboundAttachment.imageReference("img_abc")));
    assertTrue(enricher.toAgentInput(msg).contains("img_abc"));
  }

  @Test
  @DisplayName("文件本地路径转为 Agent 可消费说明")
  void fileLocalPathAttachment() {
    InboundMessage msg =
        new InboundMessage(
            "feishu",
            "ops-feishu",
            "m4",
            ChatKind.P2P,
            "u1",
            "c1",
            "",
            false,
            false,
            List.of(InboundAttachment.fileUrl("C:/tmp/report.pdf", "季度报告.pdf")));
    String input = enricher.toAgentInput(msg);
    assertTrue(input.contains("report.pdf"));
    assertTrue(input.contains("季度报告.pdf"));
    assertTrue(input.contains("文件"));
    assertTrue(input.contains("read_file"));
  }

  @Test
  @DisplayName("语音本地路径：无 ASR 时提示配置")
  void audioWithoutAsr() {
    InboundMessage msg =
        new InboundMessage(
            "feishu",
            "ops-feishu",
            "m5",
            ChatKind.P2P,
            "u1",
            "c1",
            "",
            false,
            false,
            List.of(InboundAttachment.audioUrl("C:/tmp/voice.ogg")));
    String input = enricher.toAgentInput(msg);
    assertTrue(input.contains("voice.ogg"));
    assertTrue(input.contains("语音"));
    assertTrue(input.contains("未配置语音转写"));
  }

  @Test
  @DisplayName("语音本地路径：有 ASR 时注入转写")
  void audioWithAsr() {
    DefaultInboundMediaEnricher withAsr = new DefaultInboundMediaEnricher(path -> "明天几点开会");
    InboundMessage msg =
        new InboundMessage(
            "dingtalk",
            "ops-dingtalk",
            "m6",
            ChatKind.P2P,
            "u1",
            "c1",
            "",
            false,
            false,
            List.of(InboundAttachment.audioUrl("C:/tmp/voice.ogg")));
    String input = withAsr.toAgentInput(msg);
    assertTrue(input.contains("明天几点开会"));
    assertTrue(input.contains("转写"));
  }

  @Test
  @DisplayName("ASR ffmpeg 缺失用专用文案")
  void audioAsrFfmpegMissing() {
    DefaultInboundMediaEnricher withAsr =
        new DefaultInboundMediaEnricher(
            path -> {
              throw new java.io.IOException("需安装 ffmpeg（ORYXOS_FFMPEG 或 PATH）");
            });
    InboundMessage msg =
        new InboundMessage(
            "feishu",
            "ops-feishu",
            "m7",
            ChatKind.P2P,
            "u1",
            "c1",
            "",
            false,
            false,
            List.of(InboundAttachment.audioUrl("C:/tmp/voice.silk")));
    String input = withAsr.toAgentInput(msg);
    assertTrue(input.contains("ffmpeg"));
    assertTrue(input.contains("voice.silk"));
  }

  @Test
  @DisplayName("ASR Whisper HTTP 失败用通用转写失败文案")
  void audioAsrWhisperHttpFail() {
    DefaultInboundMediaEnricher withAsr =
        new DefaultInboundMediaEnricher(
            path -> {
              throw new java.io.IOException("Whisper HTTP 400: Invalid file format");
            });
    InboundMessage msg =
        new InboundMessage(
            "feishu",
            "ops-feishu",
            "m8",
            ChatKind.P2P,
            "u1",
            "c1",
            "",
            false,
            false,
            List.of(InboundAttachment.audioUrl("C:/tmp/voice.ogg")));
    String input = withAsr.toAgentInput(msg);
    assertTrue(input.contains("语音转写失败"));
    assertTrue(input.contains("Whisper HTTP"));
  }

  @Test
  @DisplayName("视频本地路径提示落盘；有 ASR 时附音轨转写")
  void videoWithOptionalAudioAsr() {
    DefaultInboundMediaEnricher withAsr = new DefaultInboundMediaEnricher(path -> "视频里说开会");
    InboundMessage msg =
        new InboundMessage(
            "feishu",
            "ops-feishu",
            "m9",
            ChatKind.P2P,
            "u1",
            "c1",
            "",
            false,
            false,
            List.of(InboundAttachment.videoUrl("C:/tmp/clip.mp4", "会议.mp4")));
    String input = withAsr.toAgentInput(msg);
    assertTrue(input.contains("视频"));
    assertTrue(input.contains("clip.mp4"));
    assertTrue(input.contains("会议.mp4"));
    assertTrue(input.contains("音轨转写"));
    assertTrue(input.contains("视频里说开会"));
  }
}
