package eu.wohlben.qits.workspaces.wiring.testdb;

import java.util.Map;
import java.util.Set;
import org.eclipse.microprofile.config.spi.ConfigSource;

/**
 * Hands the running {@link EmbeddedPg} to every {@code @QuarkusTest} in this module, as the three
 * keys a deployment would supply — for <b>both</b> datasources this deployable opens.
 *
 * <p>Two datasources, because two jars each own one: {@code workspaces} is this context's store,
 * declared in the domain jar's {@code META-INF/microprofile-config.properties}, and {@code
 * eventstream} is the outbox's, declared in the qits-eventstream jar's. The bus is dark in {@code
 * %test} ({@code qits.eventstream.enabled=false}), and that stops publishing and sweeping — not the
 * datasource. Quarkus opens the connection and runs Flyway at boot regardless, so the outbox needs a
 * real database here exactly as the store does.
 *
 * <p>It is a config source rather than six lines in {@code
 * src/test/resources/application.properties} because the port is chosen at run time — the instance
 * takes a free one, so nothing can be written down ahead of the JVM that starts it.
 *
 * <p>The ordinal sits above application.properties (250) so this wins over both jars' shipped
 * defaults, which are the unresolvable {@code ${QITS_RESOURCE_*}} expressions a deployment fills in.
 * It is registered through {@code META-INF/services}, which is how a config source joins a Quarkus
 * application without being a bean.
 */
public class EmbeddedPgConfigSource implements ConfigSource {

  /**
   * This module's databases on the shared instance, one per datasource. Distinct per (module,
   * datasource) — {@code domain} names its own — so two suites on one host cannot mean the same one.
   */
  private static final String WORKSPACES_DATABASE = "qits_workspaces_service_test";

  private static final String EVENTSTREAM_DATABASE = "qits_workspaces_eventstream_service_test";

  private final Map<String, String> values =
      Map.of(
          "quarkus.datasource.workspaces.jdbc.url", EmbeddedPg.url(WORKSPACES_DATABASE),
          "quarkus.datasource.workspaces.username", EmbeddedPg.USER,
          "quarkus.datasource.workspaces.password", EmbeddedPg.PASSWORD,
          "quarkus.datasource.eventstream.jdbc.url", EmbeddedPg.url(EVENTSTREAM_DATABASE),
          "quarkus.datasource.eventstream.username", EmbeddedPg.USER,
          "quarkus.datasource.eventstream.password", EmbeddedPg.PASSWORD);

  @Override
  public int getOrdinal() {
    return 500;
  }

  @Override
  public Set<String> getPropertyNames() {
    return values.keySet();
  }

  @Override
  public String getValue(String propertyName) {
    return values.get(propertyName);
  }

  @Override
  public String getName() {
    return "embedded-pg";
  }
}
