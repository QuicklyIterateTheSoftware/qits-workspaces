package eu.wohlben.qits.workspaces.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import eu.wohlben.qits.workspaces.error.VersionBumpException;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The arithmetic underneath both bumpers: disjoint spans in, every other character untouched. */
public class TextSpliceTest {

  @Test
  public void noSpansIsTheIdentity() {
    String original = "nothing to do here";
    assertSame(original, TextSplice.replaceAll(original, List.of(), "x"));
  }

  @Test
  public void aSpanIsReplacedAndItsNeighboursAreNot() {
    String original = "<version>1.0.0-SNAPSHOT</version>";
    String bumped =
        TextSplice.replaceAll(original, List.of(new TextSplice.Span(9, 23)), "2026.731.193059");
    assertEquals("<version>2026.731.193059</version>", bumped);
  }

  @Test
  public void severalSpansAreReplacedRegardlessOfTheOrderTheyArriveIn() {
    String original = "a=OLD b=OLD c=OLD";
    List<TextSplice.Span> shuffled =
        List.of(new TextSplice.Span(14, 17), new TextSplice.Span(2, 5), new TextSplice.Span(8, 11));
    assertEquals("a=NEW b=NEW c=NEW", TextSplice.replaceAll(original, shuffled, "NEW"));
  }

  @Test
  public void aSpanMayStartAtTheFirstCharacterAndEndAtTheLast() {
    assertEquals("NEW", TextSplice.replaceAll("OLD", List.of(new TextSplice.Span(0, 3)), "NEW"));
  }

  @Test
  public void anEmptySpanInsertsWithoutDeleting() {
    assertEquals("a!b", TextSplice.replaceAll("ab", List.of(new TextSplice.Span(1, 1)), "!"));
  }

  @Test
  public void aReplacementOfADifferentLengthDoesNotDisturbTheSpansAfterIt() {
    // The bug this rules out: applying spans left to right against a growing buffer. Every version
    // bump changes the length, so a locator's later offsets are only valid against the ORIGINAL.
    String original = "x=OLD y=OLD";
    List<TextSplice.Span> spans =
        List.of(new TextSplice.Span(2, 5), new TextSplice.Span(8, 11));
    assertEquals("x=2026.731.193059 y=2026.731.193059",
        TextSplice.replaceAll(original, spans, "2026.731.193059"));
  }

  @Test
  public void overlappingSpansFailLoudly() {
    VersionBumpException thrown =
        assertThrows(
            VersionBumpException.class,
            () ->
                TextSplice.replaceAll(
                    "abcdefgh",
                    List.of(new TextSplice.Span(1, 5), new TextSplice.Span(3, 7)),
                    "X"));
    assertEquals(true, thrown.getMessage().contains("overlapping"), thrown.getMessage());
  }

  @Test
  public void aSpanPastTheEndFailsLoudly() {
    assertThrows(
        VersionBumpException.class,
        () -> TextSplice.replaceAll("abc", List.of(new TextSplice.Span(1, 9)), "X"));
  }

  @Test
  public void aBackwardsOrNegativeSpanCannotBeConstructed() {
    assertThrows(VersionBumpException.class, () -> new TextSplice.Span(5, 2));
    assertThrows(VersionBumpException.class, () -> new TextSplice.Span(-1, 2));
  }
}
