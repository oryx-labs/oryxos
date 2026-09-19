package io.oryxos.provider;

import io.oryxos.core.embedding.TextEmbedder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 内置 mock 向量化：文本 SHA-256 播种的确定性伪随机单位向量——同一文本恒得同一向量、无网络、无 key（SC-004：CI 可稳定断言）。语义无意义但排序确定，足以走通检索全链路。
 */
public final class MockEmbeddingModel implements TextEmbedder {

  /** mock 向量维度：够小以省内存，够大以让不同文本的向量可区分。 */
  static final int DIMENSIONS = 64;

  static final String MODEL_ID = "mock/deterministic";

  @Override
  public float[] embed(String text) {
    byte[] seed = sha256(text == null ? "" : text);
    float[] vector = new float[DIMENSIONS];
    byte[] block = seed;
    int produced = 0;
    int counter = 0;
    double normSquared = 0;
    while (produced < DIMENSIONS) {
      for (int offset = 0;
          offset + Integer.BYTES <= block.length && produced < DIMENSIONS;
          offset += Integer.BYTES) {
        int bits =
            ((block[offset] & 0xFF) << 24)
                | ((block[offset + 1] & 0xFF) << 16)
                | ((block[offset + 2] & 0xFF) << 8)
                | (block[offset + 3] & 0xFF);
        // 映射到 [-1, 1)：整数除以 2^31，保持确定性且分布均匀
        float value = bits / 2147483648.0f;
        vector[produced] = value;
        produced++;
        normSquared += (double) value * value;
      }
      counter++;
      block = sha256(bytesToHex(seed) + ":" + counter);
    }
    float norm = (float) Math.sqrt(normSquared);
    if (norm > 0) {
      for (int i = 0; i < DIMENSIONS; i++) {
        vector[i] /= norm;
      }
    }
    return vector;
  }

  @Override
  public String modelId() {
    return MODEL_ID;
  }

  @Override
  public int dimensions() {
    return DIMENSIONS;
  }

  private static byte[] sha256(String text) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("JVM 缺少 SHA-256 实现", e);
    }
  }

  private static String bytesToHex(byte[] bytes) {
    StringBuilder hex = new StringBuilder(bytes.length * 2);
    for (byte b : bytes) {
      hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
    }
    return hex.toString();
  }
}
