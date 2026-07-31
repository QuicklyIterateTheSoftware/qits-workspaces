package eu.wohlben.qits.workspaces.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.TimeZone;
import org.junit.jupiter.api.Test;

/**
 * The version format, pinned at every edge it was designed to survive.
 *
 * <p>The two real comparators — npm's bundled {@code semver} and Maven's {@code ComparableVersion}
 * from {@code maven-artifact-3.9.12} — were run against this format while it was being ruled, and
 * both agreed with chronological order in every case. Neither is on this repository's classpath and
 * neither is going to be: a clone of this repo alone must build. What is asserted here is the
 * property both comparators reduce to for a version with three numeric identifiers and no
 * qualifier — compare the identifiers as integers, left to right — plus the structural facts that
 * make that reduction legal, above all that <b>no identifier ever has a leading zero</b>. That is
 * the bug the format exists to make impossible, and it is the one that would otherwise pass every
 * daytime test and detonate on the first release before 10:00.
 */
public class VersionStampTest {

  private static Instant utc(String isoLocal) {
    return LocalDateTime.parse(isoLocal).toInstant(ZoneOffset.UTC);
  }

  @Test
  public void theAfternoonCaseIsTheShapeThatWasAskedFor() {
    assertEquals("2026.731.193059", VersionStamp.of(utc("2026-07-31T19:30:59")));
  }

  @Test
  public void theMorningCaseIsWhyTheDashedFormWasRejected() {
    // 2026.7.31-093059 is INVALID semver and 2026.7.31-93059 changes meaning; here the same wall
    // clock is simply a smaller third identifier, and 09:30 sorts before 19:30 for free.
    assertEquals("2026.731.93059", VersionStamp.of(utc("2026-07-31T09:30:59")));
    assertTrue(
        numericallyBefore("2026.731.93059", "2026.731.193059"),
        "09:30:59 must sort before 19:30:59 on the same day");
  }

  @Test
  public void midnightOnTheFirstOfJanuaryIsTheAllZeroesCase() {
    // Single-digit month AND single-digit day AND a zero time: the case with the most opportunities
    // to grow a leading zero, and it has none because nothing is ever zero-padded.
    assertEquals("2026.101.0", VersionStamp.of(utc("2026-01-01T00:00:00")));
  }

  @Test
  public void theLastSecondOfTheYearIsTheLargestStampThatYearCanProduce() {
    assertEquals("2026.1231.235959", VersionStamp.of(utc("2026-12-31T23:59:59")));
  }

  @Test
  public void singleDigitMonthsAndDaysFoldIntoOneNumberWithoutColliding() {
    // Jan 15 -> 115, Feb 3 -> 203. Read as month*100+day they stay ordered; read as a naive
    // concatenation they would not.
    assertEquals("2026.115.0", VersionStamp.of(utc("2026-01-15T00:00:00")));
    assertEquals("2026.203.0", VersionStamp.of(utc("2026-02-03T00:00:00")));
    assertTrue(numericallyBefore("2026.115.0", "2026.203.0"), "Jan 15 must sort before Feb 3");
  }

  @Test
  public void singleDigitHoursMinutesAndSecondsShrinkTheThirdIdentifier() {
    assertEquals("2026.1005.1", VersionStamp.of(utc("2026-10-05T00:00:01")));
    assertEquals("2026.101.9", VersionStamp.of(utc("2026-01-01T00:00:09")));
    assertEquals("2026.101.100", VersionStamp.of(utc("2026-01-01T00:01:00")));
    assertEquals("2026.101.10000", VersionStamp.of(utc("2026-01-01T01:00:00")));
  }

  @Test
  public void theYearRollsOverUpwards() {
    assertEquals("2026.1231.235959", VersionStamp.of(utc("2026-12-31T23:59:59")));
    assertEquals("2027.101.0", VersionStamp.of(utc("2027-01-01T00:00:00")));
    assertTrue(numericallyBefore("2026.1231.235959", "2027.101.0"), "the year must carry");
  }

  @Test
  public void aLeapDayIsAnOrdinaryDay() {
    assertEquals("2028.229.0", VersionStamp.of(utc("2028-02-29T00:00:00")));
    assertTrue(numericallyBefore("2028.228.235959", "2028.229.0"));
    assertTrue(numericallyBefore("2028.229.235959", "2028.301.0"));
  }

  @Test
  public void theCrossDayAndCrossYearOrderingsMeasuredInThePlanHold() {
    assertTrue(numericallyBefore("2026.731.193059", "2026.801.93059"), "cross-day");
    assertTrue(numericallyBefore("2026.1231.235959", "2027.101.0"), "cross-year");
  }

  @Test
  public void theStampIsReadInUtcAndNotInTheHostZone() {
    // 23:30 UTC on 31 July is already 1 August in a +14 zone and still 31 July in a -11 one. A
    // stamp that moved with the host would make one release read as two different days depending on
    // who produced it — so the zone is a constant, and this is the assertion that holds it there.
    assertEquals(ZoneOffset.UTC, VersionStamp.ZONE);
    Instant lateJuly = Instant.parse("2026-07-31T23:30:00Z");
    TimeZone original = TimeZone.getDefault();
    try {
      TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Kiritimati"));
      assertEquals("2026.731.233000", VersionStamp.of(lateJuly), "+14 host");
      TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Pago_Pago"));
      assertEquals("2026.731.233000", VersionStamp.of(lateJuly), "-11 host");
    } finally {
      TimeZone.setDefault(original);
    }
  }

  @Test
  public void twoStampsInTheSameSecondAreEqual() {
    // One second is the resolution, stated rather than accidental: it is why the integrate flow
    // takes the stamp ONCE and threads it through, instead of recomputing it per file.
    Instant at = Instant.parse("2026-07-31T19:30:59Z");
    assertEquals(VersionStamp.of(at), VersionStamp.of(at.plusMillis(999)));
    assertTrue(numericallyBefore(VersionStamp.of(at), VersionStamp.of(at.plusSeconds(1))));
  }

  @Test
  public void everyStampOfAWholeYearIsThreeNumericIdentifiersWithNoLeadingZero() {
    Instant at = utc("2026-01-01T00:00:00");
    Instant end = utc("2027-01-01T00:00:00");
    while (at.isBefore(end)) {
      String stamp = VersionStamp.of(at);
      String[] identifiers = stamp.split("\\.", -1);
      assertEquals(3, identifiers.length, stamp);
      for (String identifier : identifiers) {
        assertTrue(identifier.matches("[0-9]+"), stamp + " has a non-numeric identifier");
        assertTrue(
            identifier.length() == 1 || identifier.charAt(0) != '0',
            stamp + " has a leading-zero identifier, which semver forbids outright");
        Long.parseLong(identifier);
      }
      at = at.plusSeconds(1013);
    }
  }

  @Test
  public void theStampIsStrictlyMonotonicOverAWholeYear() {
    // The property that makes this a version and not just a timestamp: later instant, larger
    // version, at every scale, under the comparison both ecosystems perform on all-numeric
    // identifiers.
    Instant at = utc("2026-01-01T00:00:00");
    Instant end = utc("2027-01-01T00:00:00");
    String previous = VersionStamp.of(at);
    while (at.isBefore(end)) {
      at = at.plusSeconds(1013);
      String current = VersionStamp.of(at);
      assertTrue(numericallyBefore(previous, current), previous + " should sort before " + current);
      previous = current;
    }
  }

  @Test
  public void everyMidnightOfThreeYearsCarriesUpwards() {
    // The step above skips over most day boundaries; this walks every single one, including the
    // month ends, the leap day and the two new years.
    LocalDate day = LocalDate.of(2026, 1, 1);
    LocalDate end = LocalDate.of(2029, 1, 1);
    while (day.isBefore(end)) {
      String lastSecond = VersionStamp.of(day.atTime(23, 59, 59).toInstant(ZoneOffset.UTC));
      LocalDate next = day.plusDays(1);
      String firstSecond = VersionStamp.of(next.atStartOfDay().toInstant(ZoneOffset.UTC));
      assertTrue(
          numericallyBefore(lastSecond, firstSecond),
          day + " -> " + next + ": " + lastSecond + " should sort before " + firstSecond);
      day = next;
    }
  }

  @Test
  public void anInstantOutsideTheCommonEraIsRefusedRatherThanRendered() {
    Instant beforeYearOne = LocalDateTime.of(-5, 1, 1, 0, 0).toInstant(ZoneOffset.UTC);
    assertThrows(IllegalArgumentException.class, () -> VersionStamp.of(beforeYearOne));
  }

  /**
   * Compare two stamps the way both ecosystems compare an all-numeric, qualifier-free version:
   * identifier by identifier, numerically. Deliberately not a string comparison — {@code "1231"}
   * sorts before {@code "731"} lexically, and that difference is the whole reason the identifiers
   * must be numeric rather than zero-padded text.
   */
  private static boolean numericallyBefore(String a, String b) {
    String[] left = a.split("\\.");
    String[] right = b.split("\\.");
    assertEquals(left.length, right.length, "both stamps must have the same shape");
    for (int i = 0; i < left.length; i++) {
      long l = Long.parseLong(left[i]);
      long r = Long.parseLong(right[i]);
      if (l != r) {
        return l < r;
      }
    }
    return false;
  }
}
