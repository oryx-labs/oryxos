package io.oryxos.channel.alipay;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AlipayRsa2Test {

  @Test
  @DisplayName("RSA2 签名验签回环")
  void roundTrip() throws Exception {
    KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
    gen.initialize(2048);
    KeyPair pair = gen.generateKeyPair();
    String priv = Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded());
    String pub = Base64.getEncoder().encodeToString(pair.getPublic().getEncoded());
    AlipayRsa2 rsa = new AlipayRsa2(priv, pub, pub);
    Map<String, String> params = new LinkedHashMap<>();
    params.put("biz_content", "<XML/>");
    params.put("charset", "UTF-8");
    params.put("service", "alipay.service.check");
    params.put("sign_type", "RSA2");
    String sign = rsa.sign(params, StandardCharsets.UTF_8);
    params.put("sign", sign);
    assertTrue(rsa.verify(params, StandardCharsets.UTF_8));
  }
}
