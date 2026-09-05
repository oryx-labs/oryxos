package io.oryxos.core.channel;

import java.nio.file.Path;

/** 入站语音转写（飞书/钉钉等仅给音频文件、无平台 ASR 时使用）。企微智能机器人语音已带转写文本，不走本接口。 */
@FunctionalInterface
public interface InboundSpeechTranscriber {

  /**
   * 将本地音频转为文本。
   *
   * @throws Exception 转写失败（网络、格式、未配置等）；调用方应降级为可读提示，不阻断编排时可选吞掉
   */
  String transcribe(Path audioFile) throws Exception;
}
