package eu.wohlben.qits.workspaces.testdb;

import java.util.Map;
import java.util.Set;
import org.eclipse.microprofile.config.spi.ConfigSource;

/**
 * Hands the running {@link EmbeddedPg} to every {@code @QuarkusTest} in this module, as the three
 * keys a deployment would supply for the {@code workspaces} datasource: {@code jdbc.url}, {@code
 * username}, {@code password}.
 *
 * <p>It is a config source rather than three lines in {@code
 * src/test/resources/application.properties} because the port is chosen at run time — the instance
 * takes a free one, so nothing can be written down ahead of the JVM that starts it.
 *
 * <p>The ordinal sits above application.properties (250) so this wins over the shipped defaults in
 * the domain jar, whose value is the unresolvable {@code ${QITS_RESOURCE_DB_URL}} expression. It is
 * registered through {@code META-INF/services}, which is how a config source joins a Quarkus
 * application without being a bean.
 */
public class EmbeddedPgConfigSource implements ConfigSource {

  /**
   * This module's own database on the shared instance. Distinct per (module, datasource): {@code
   * service} names three others, and two suites on one host must not be able to mean the same one.
   */
  private static final String DATABASE = "qits_workspaces_domain_test";

  private static final String PREFIX = "quarkus.datasource.workspaces.";

  private final Map<String, String> values =
      Map.of(
          PREFIX + "jdbc.url", EmbeddedPg.url(DATABASE),
          PREFIX + "username", EmbeddedPg.USER,
          PREFIX + "password", EmbeddedPg.PASSWORD);

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
