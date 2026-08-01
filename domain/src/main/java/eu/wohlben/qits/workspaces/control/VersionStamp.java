package eu.wohlben.qits.workspaces.control;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * The release version an integrate stamps: {@code YYYY.MMDD.HHMMSS}, one canonical string for both
 * build stacks.
 *
 * <pre>
 * 2026-07-31 19:30:59  ->  2026.731.193059
 * 2026-07-31 09:30:59  ->  2026.731.93059
 * 2026-01-01 00:00:00  ->  2026.101.0
 * 2026-12-31 23:59:59  ->  2026.1231.235959
 * </pre>
 *
 * <p><b>Why the punctuation moved.</b> The shape asked for was {@code $year.$month.$day-HHMMSS}, and
 * it cannot be used: {@code 2026.07.31-193059} is invalid semver (a leading zero in a numeric
 * identifier), and the obvious repair {@code 2026.7.31-193059} is valid at 19:30:59 and <b>invalid
 * at 09:30:59</b> — {@code 093059} is an all-digit prerelease identifier with a leading zero, which
 * semver §9 forbids. That form works every afternoon and fails every morning. Folding month+day and
 * the time into single numeric identifiers keeps every digit the user asked for and moves only the
 * punctuation.
 *
 * <p><b>Integer arithmetic, not string formatting.</b> Each of the three identifiers is computed as
 * a number and rendered by {@code Integer.toString}, so <b>no leading zero can exist</b> — there is
 * no zero-padding step to forget. That is the entire reason for the shape, and {@code
 * VersionStampTest} asserts it over a year of instants.
 *
 * <p><b>It is a release, not a prerelease.</b> No dash, no qualifier: npm's {@code semver} sees
 * three numeric identifiers and Maven's {@code ComparableVersion} sees three integer items, so caret
 * ranges, {@code maxSatisfying}, {@code latest} resolution and Maven ordering all behave. A dashed
 * suffix would mean <i>prerelease</i> to npm (sorting <i>before</i> the release) and <i>unknown
 * qualifier</i> to Maven (sorting <i>after</i> it) — one string, two opposite meanings. Both
 * comparators were run against both shapes before this was ruled; the measurements are in the
 * release-flow plan.
 *
 * <p><b>UTC, pinned.</b> The rendering is the UTC wall clock of the instant. The version is a
 * platform-wide identity that has to be reproducible from the commit alone, and a host-local zone
 * would make the same release read as two different days depending on who looked. The plan rules the
 * format but names no zone; UTC is that choice, made here once and asserted by the tests rather than
 * left to {@code ZoneId.systemDefault()}.
 *
 * <p><b>One-second resolution, and the tag is what makes a tie safe.</b> Two stamps taken in the
 * same second are equal, and nothing about the stamp prevents that. This comment used to say the
 * fast-forward push rejected such a tie; <b>it does not</b>. The repository lease is held across the
 * whole land operation including the push, so two releases are sequential: the second builds its
 * worktree from the first's commit and its push is a clean fast-forward. The compare-and-swap never
 * fires, and two commits would carry one version.
 *
 * <p>What does reject it is the <b>release tag</b>, which is this string exactly. A non-forced push
 * cannot overwrite an existing tag ref and the release push is atomic, so the second release is
 * refused whole and lands nothing — see {@code ReleaseIntegrator} and {@code
 * IntegrateConflictException.Reason.VERSION_ALREADY_RELEASED}. Reachability is very low (a release
 * is comfortably over a second of work), but it is a guarantee now rather than an assumption.
 *
 * <p>None of that changes why the stamp is taken <i>once</i> at the start of an integrate and
 * threaded through rather than recomputed per file: a slow bump would otherwise write two versions
 * into one commit.
 */
public final class VersionStamp {

  /**
   * The zone the wall clock is read in. Deliberately a constant rather than a parameter: a version
   * whose meaning depends on the host that produced it is not an identity.
   */
  public static final ZoneOffset ZONE = ZoneOffset.UTC;

  private VersionStamp() {}

  /**
   * The version string for an instant.
   *
   * @throws IllegalArgumentException if the instant falls outside the common era, where the year
   *     component would render as zero or negative and stop being a numeric identifier at all
   */
  public static String of(Instant at) {
    LocalDateTime t = LocalDateTime.ofInstant(at, ZONE);
    int year = t.getYear();
    if (year < 1) {
      throw new IllegalArgumentException(
          "a version stamp needs a positive year, got " + year + " for " + at);
    }
    int monthDay = t.getMonthValue() * 100 + t.getDayOfMonth();
    int time = t.getHour() * 10000 + t.getMinute() * 100 + t.getSecond();
    return year + "." + monthDay + "." + time;
  }
}
