package eu.wohlben.qits.workspaces;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.junit.ArchTests;
import eu.wohlben.qits.archrules.CausationRowRules;

/**
 * The platform's shared ArchUnit rules over this repository's classes. Today that is the
 * causation-row completeness guard: every {@code @Entity} either implements {@code CausedRow} (and
 * lists {@code CausationStamp} in its {@code @EntityListeners}) or declares {@code @Uncaused}, so a
 * new entity that skips the decision fails this build naming the class instead of leaving a silent
 * hole in the trace. It runs here because this is the module the entities live in. The rule set
 * lives in qits-arch-rules (qits-integrations-quarkus); a new set added there arrives as one more
 * {@code @ArchTest} line.
 */
@AnalyzeClasses(
    packages = "eu.wohlben.qits.workspaces",
    importOptions = ImportOption.DoNotIncludeTests.class)
class ArchRulesTest {

  @ArchTest static final ArchTests CAUSATION = ArchTests.in(CausationRowRules.class);
}
