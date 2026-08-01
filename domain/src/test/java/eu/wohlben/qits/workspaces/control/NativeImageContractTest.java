package eu.wohlben.qits.workspaces.control;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.runtime.annotations.RegisterForReflection;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * The two things this module ships that only a GraalVM binary can get wrong, pinned where the JVM
 * suite can still see them.
 *
 * <p>Neither assertion can prove a native image works — that needs the binary, and both defects
 * below were found by running one. What they prevent is the silent re-introduction: the suite runs
 * on in-memory H2 and needs no reflection registration, so the shipped datasource url and the
 * {@code @RegisterForReflection} lists are exactly the code no other test looks at.
 */
public class NativeImageContractTest {

  /**
   * The shipped url must not ask H2 to start its TCP server. {@code AUTO_SERVER=TRUE} loads {@code
   * org.h2.server.TcpServer} by name, which is not in the image, and the binary died on its first
   * connection — see this file's header comment for why registering the server was the wrong fix.
   * Read from the resource rather than from the config, because the suite overrides the url.
   */
  @Test
  public void shippedDatasourceUrlDoesNotStartH2sTcpServer() throws Exception {
    Properties shipped = new Properties();
    try (InputStream in =
        getClass().getClassLoader().getResourceAsStream("META-INF/microprofile-config.properties")) {
      shipped.load(in);
    }
    String url = shipped.getProperty("quarkus.datasource.workspaces.jdbc.url");
    assertTrue(url != null && url.startsWith("jdbc:h2:file:"), "expected a file H2 url, got " + url);
    assertFalse(
        url.toUpperCase().contains("AUTO_SERVER"),
        "AUTO_SERVER makes the native binary die on `Class \"org.h2.server.TcpServer\" not found`,"
            + " and opens a database port to every workspace container on qits-net: "
            + url);
  }

  /**
   * {@code WorkspaceDaemonRegistry} deserializes {@link QitsConfig} with a bare {@code
   * ObjectMapper}, so nothing on a JAX-RS signature names it and native-image keeps no members
   * unless it is registered. {@code @RegisterForReflection} does not descend into nested types, so
   * the risk is not the annotation going missing — it is a new nested record being added above it
   * and quietly not registered, which turns into an empty config and a warning that reads like a
   * daemon bug.
   */
  @Test
  public void everyNestedQitsConfigRecordIsRegisteredForReflection() {
    Set<Class<?>> registered =
        Set.of(QitsConfig.class.getAnnotation(RegisterForReflection.class).targets());
    List<Class<?>> nested =
        Arrays.stream(QitsConfig.class.getDeclaredClasses()).filter(Class::isRecord).toList();

    assertTrue(registered.contains(QitsConfig.class), "QitsConfig itself is not registered");
    List<String> missing =
        nested.stream()
            .filter(c -> !registered.contains(c))
            .map(Class::getSimpleName)
            .collect(Collectors.toList());
    assertTrue(
        missing.isEmpty(),
        "nested records not in QitsConfig's @RegisterForReflection(targets): " + missing);
  }

  /**
   * The enums the record tree binds are targets too. Jackson resolves an enum's constants
   * reflectively, so an unregistered enum makes the binary's {@code QitsConfig} read throw — caught
   * and degraded to the EMPTY config, which auto-started nothing for any workspace whose services
   * declared {@code restart-policy:} or a health-check {@code kind:}. Measured live (D1); the JVM
   * suite could not see it, which is why the registration is pinned here. Found by walking the
   * record components rather than naming the two enums, so the next enum-typed field joins the
   * assertion by existing.
   */
  @Test
  public void everyEnumBoundByTheQitsConfigTreeIsRegisteredForReflection() {
    Set<Class<?>> registered =
        Set.of(QitsConfig.class.getAnnotation(RegisterForReflection.class).targets());
    Set<Class<?>> enums =
        registered.stream()
            .filter(Class::isRecord)
            .flatMap(record -> Arrays.stream(record.getRecordComponents()))
            .map(java.lang.reflect.RecordComponent::getType)
            .filter(Class::isEnum)
            .collect(Collectors.toSet());

    List<String> missing =
        enums.stream()
            .filter(e -> !registered.contains(e))
            .map(Class::getSimpleName)
            .collect(Collectors.toList());
    assertTrue(
        missing.isEmpty(),
        "enums bound by QitsConfig's record tree but not in @RegisterForReflection(targets): "
            + missing);
  }
}
