package eu.wohlben.qits.domain.repository.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.Test;

/** Unit tests for the git-host resolution heuristic (no Quarkus/docker needed). */
public class QitsHostResolverTest {

  @Test
  public void anExplicitValueIsRespectedVerbatim() {
    assertEquals("10.0.0.5", QitsHostResolver.resolve("10.0.0.5"));
    assertEquals("host.docker.internal", QitsHostResolver.resolve("host.docker.internal"));
    assertEquals("my-host.example", QitsHostResolver.resolve("my-host.example"));
  }

  @Test
  public void autoResolvesToADetectedHost() {
    // On WSL2 this is the eth0 IP; on plain Linux it is host.docker.internal. Either way it is a
    // usable, non-blank host — never the literal sentinel.
    String resolved = QitsHostResolver.resolve("auto");
    assertNotNull(resolved);
    assertEquals(false, resolved.isBlank());
    assertEquals(false, "auto".equals(resolved));
  }

  @Test
  public void autoOnWsl2PicksThePrimaryLanIpv4() {
    assumeTrue(QitsHostResolver.isWsl2(), "only meaningful on WSL2");
    String lan = QitsHostResolver.primaryLanIpv4();
    assertNotNull(lan, "WSL2 should expose a primary LAN IPv4");
    assertEquals(
        lan, QitsHostResolver.resolve("auto"), "auto should use the detected LAN IP on WSL2");
  }
}
