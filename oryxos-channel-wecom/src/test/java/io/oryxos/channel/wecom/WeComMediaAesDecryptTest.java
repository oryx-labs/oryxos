package io.oryxos.channel.wecom;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import io.oryxos.core.channel.ChatKind;
import io.oryxos.core.channel.InboundAttachment;
import io.oryxos.core.channel.InboundMessage;
import io.oryxos.core.session.ImageMime;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WeComMediaAesDecryptTest {

  @TempDir Path mediaRoot;

  private HttpServer server;
  private String baseUrl;
  private byte[] encryptedJpeg;
  private String aesKeyB64;

  @BeforeEach
  void startServer() throws Exception {
    byte[] key = new byte[32];
    for (int i = 0; i < key.length; i++) {
      key[i] = (byte) (i + 1);
    }
    aesKeyB64 = Base64.getEncoder().encodeToString(key);
    byte[] jpeg =
        new byte[] {
          (byte) 0xFF,
          (byte) 0xD8,
          (byte) 0xFF,
          0x01,
          0x02,
          0x03,
          0x04,
          0x05,
          0x06,
          0x07,
          0x08,
          0x09,
          0x0A,
          0x0B,
          0x0C,
          0x0D
        };
    encryptedJpeg = encryptPkcs7To32(jpeg, key);

    server = HttpServer.create(new InetSocketAddress(0), 0);
    baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    server.createContext(
        "/enc",
        exchange -> {
          exchange.sendResponseHeaders(200, encryptedJpeg.length);
          try (OutputStream out = exchange.getResponseBody()) {
            out.write(encryptedJpeg);
          }
        });
    server.start();
  }

  @AfterEach
  void stopServer() {
    if (server != null) {
      server.stop(0);
    }
  }

  @Test
  @DisplayName("AES 往返解密得到明文")
  void decryptRoundTrip() {
    byte[] plain = new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 1, 2, 3};
    byte[] key = new byte[32];
    for (int i = 0; i < 32; i++) {
      key[i] = (byte) (40 + i);
    }
    byte[] enc = encryptPkcs7To32(plain, key);
    byte[] out = WeComMediaAesDecrypt.decrypt(enc, Base64.getEncoder().encodeToString(key));
    assertArrayEquals(plain, out);
  }

  @Test
  @DisplayName("带 aeskey 下载后落盘为可识别 JPEG")
  void downloadsAndDecryptsToLocalJpeg() throws Exception {
    String remote = baseUrl + "/enc";
    WeComInboundImageResolver resolver =
        new WeComInboundImageResolver(
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
            mediaRoot,
            "ops-wecom",
            true);
    InboundMessage input =
        new InboundMessage(
            "wecom",
            "ops-wecom",
            "msg-enc",
            ChatKind.P2P,
            "u1",
            "chat-1",
            "",
            false,
            false,
            List.of(new InboundAttachment(InboundAttachment.TYPE_IMAGE, remote, aesKeyB64)));

    InboundMessage out = resolver.resolve(input);

    assertEquals(1, out.attachments().size());
    InboundAttachment att = out.attachments().get(0);
    assertEquals(remote, att.reference());
    Path local = Path.of(att.url());
    assertTrue(Files.isRegularFile(local), att.url());
    assertTrue(ImageMime.hasRecognizedMagic(local), "decrypted file should have jpeg magic");
  }

  /** 企微文档：PKCS#7 填充至 32 字节倍数后 AES-CBC。 */
  private static byte[] encryptPkcs7To32(byte[] plain, byte[] key) {
    try {
      int padLen = 32 - (plain.length % 32);
      if (padLen == 0) {
        padLen = 32;
      }
      byte[] padded = new byte[plain.length + padLen];
      System.arraycopy(plain, 0, padded, 0, plain.length);
      for (int i = plain.length; i < padded.length; i++) {
        padded[i] = (byte) padLen;
      }
      Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
      byte[] iv = new byte[16];
      System.arraycopy(key, 0, iv, 0, 16);
      cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
      return cipher.doFinal(padded);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
