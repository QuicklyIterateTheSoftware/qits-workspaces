package eu.wohlben.qits.workspaces.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.runtime.annotations.RegisterForReflection;
import io.quarkus.test.junit.QuarkusTest;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

/**
 * The service module's half of the native-image contract — the parts of it a JVM run can still
 * check. See {@code eu.wohlben.qits.workspaces.control.NativeImageContractTest} in {@code domain}
 * for the same idea over the shipped datasource url and {@code QitsConfig}.
 *
 * <p>Both defects pinned here were found by probing the binary and are invisible from a passing
 * suite: on the JVM, reflection needs no registration and {@code application.properties} is an
 * ordinary runtime config source.
 */
@QuarkusTest
public class NativeImageContractTest {

  /**
   * {@code quarkus.rest.path} is a build-time config item and is therefore absent from a native
   * image's <em>runtime</em> config. {@link CaptureCorsRoute} is a raw Vert.x route that has to
   * build its own path, so reading the Quarkus key gave it the {@code defaultValue} in the binary:
   * the preflight registered on {@code /capture} and the real endpoint answered browsers with
   * RESTEasy's bare 200 and no CORS headers. The application-owned key it reads instead is
   * runtime-phase in both runtimes; this test is what keeps someone from "simplifying" it back.
   */
  @Test
  public void captureCorsRouteReadsTheApplicationOwnedRestPath() throws Exception {
    ConfigProperty configured =
        CaptureCorsRoute.class.getDeclaredField("restPath").getAnnotation(ConfigProperty.class);
    assertEquals(
        "qits.rest.path",
        configured.name(),
        "a raw router route must not read a build-time quarkus.* key: it resolves to the"
            + " defaultValue in a native image");
  }

  @ConfigProperty(name = "qits.rest.path")
  String ownedRestPath;

  @ConfigProperty(name = "quarkus.rest.path")
  String quarkusRestPath;

  /** And the Quarkus key still has to end up at the same place, or the preflight misses the POST. */
  @Test
  public void quarkusRestPathIsDerivedFromTheApplicationOwnedOne() {
    assertEquals(
        ownedRestPath, quarkusRestPath, "quarkus.rest.path must stay derived from qits.rest.path");
  }

  /**
   * The capture payload never appears on a signature Quarkus can index — the request is taken as
   * {@code byte[]} and the reply hidden inside a {@code jakarta.ws.rs.core.Response} — so every one
   * of its records has to be registered by hand, and {@code @RegisterForReflection} does not descend
   * into nested types. Unregistered, the binary answered 400 "Malformed capture payload" to
   * everything.
   */
  @Test
  public void everyCapturePayloadRecordIsRegisteredForReflection() {
    Set<Class<?>> registered =
        Set.of(CaptureResource.class.getAnnotation(RegisterForReflection.class).targets());
    List<String> missing =
        Stream.concat(
                Stream.of(
                    CaptureResource.CaptureRequest.class, CaptureResource.CaptureResponse.class),
                Stream.of(
                        CaptureResource.CaptureRequest.class.getDeclaredClasses(),
                        CaptureResource.CaptureResponse.class.getDeclaredClasses())
                    .flatMap(Arrays::stream))
            .filter(Class::isRecord)
            .filter(c -> !registered.contains(c))
            .map(Class::getSimpleName)
            .toList();
    assertTrue(
        missing.isEmpty(),
        "capture records not in CaptureResource's @RegisterForReflection(targets): " + missing);
  }
}
