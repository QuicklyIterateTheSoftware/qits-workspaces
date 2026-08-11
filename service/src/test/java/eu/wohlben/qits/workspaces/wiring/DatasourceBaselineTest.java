package eu.wohlben.qits.workspaces.wiring;

import eu.wohlben.qits.archrules.DatasourceBaselineRules;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

/**
 * The platform's datasource resilience baseline, as a build failure: every postgresql datasource
 * this application declares opens its connections through {@code PatientPgDriver}, validates on
 * borrow and waits 15S to acquire. The rule and the measurements behind each line live in
 * qits-arch-rules and in the superproject's {@code db-patience-plan.md}.
 *
 * <p><b>It runs in this module, not in {@code domain}, because this is the module that sees the
 * whole config.</b> Two postgresql datasources reach the deployable: {@code workspaces}, whose
 * block the domain jar ships, and {@code eventstream}, whose block the qits-eventstream jar ships.
 * A test in {@code domain} would miss the deployable's own {@code application.properties}, which is
 * where the second one's driver line currently sits — and a baseline asserted over half the pools
 * is a baseline that passes while a cutover still fails every request through the other half.
 *
 * <p>It is a {@code @QuarkusTest} for the same reason: {@code ConfigProvider.getConfig()} has to be
 * the application's config, merged from every source in the order the running process merges them,
 * rather than whatever a bare classpath scan happens to find first.
 */
@QuarkusTest
class DatasourceBaselineTest {

  @Test
  void everyPostgresDatasourceCarriesTheBaseline() {
    DatasourceBaselineRules.assertBaseline();
  }
}
