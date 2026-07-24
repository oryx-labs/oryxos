package io.oryxos.core.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SkillGlobalStateConcurrencyTest {

  @Test
  void globalDisableWaitsForTheCurrentRequestLease(@TempDir Path root) throws Exception {
    SkillPackageTestSupport.Market market = SkillPackageTestSupport.market(root, "ops");
    market
        .management()
        .importZip(
            new ByteArrayInputStream(SkillPackageTestSupport.validSkillZip("weather")),
            "weather.zip");
    market.associations().associate("ops", "weather");
    SkillLease request = market.graph().openRequest("ops");
    CountDownLatch started = new CountDownLatch(1);
    AtomicReference<Throwable> failure = new AtomicReference<>();
    Thread writer =
        Thread.ofPlatform()
            .start(
                () -> {
                  started.countDown();
                  try {
                    market.management().setEnabled("weather", false);
                  } catch (Throwable error) {
                    failure.set(error);
                  }
                });

    assertTrue(started.await(Duration.ofSeconds(2).toMillis(), TimeUnit.MILLISECONDS));
    assertFalse(Files.exists(root.resolve("skills/weather/.oryxos-disabled")));
    request.close();
    writer.join(Duration.ofSeconds(2).toMillis());

    assertFalse(writer.isAlive());
    assertEquals(null, failure.get());
    assertTrue(Files.isRegularFile(root.resolve("skills/weather/.oryxos-disabled")));
  }
}
