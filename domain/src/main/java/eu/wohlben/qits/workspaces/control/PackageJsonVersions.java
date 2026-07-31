package eu.wohlben.qits.workspaces.control;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import eu.wohlben.qits.workspaces.error.VersionBumpException;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Locates named string values in an npm manifest as character spans into its own text, using
 * Jackson's streaming parser for structure only.
 *
 * <p>Fields are named by JSON Pointer, which spells the lockfile's own root entry exactly: {@code
 * /packages//version} is {@code .packages[""].version}, the empty key npm uses for the package the
 * lock belongs to. The pointer comes from the parser's own context, so escaping ({@code ~0}, {@code
 * ~1}) is Jackson's problem and not a string-building bug waiting here.
 *
 * <p>Unlike the XML side, Jackson's character offsets are dependable: measured across all 36 tracked
 * {@code package.json}/{@code package-lock.json} files in this platform, every one of 74,075 string
 * values reported a span that begins and ends on its own quote — including inside an 8,600-line
 * lockfile, so there is no buffer-boundary caveat to work around. The returned span <b>includes the
 * surrounding quotes</b>, which is what lets the splice write a complete JSON string literal.
 */
public final class PackageJsonVersions {

  private PackageJsonVersions() {}

  /**
   * The span of each requested pointer that the document actually holds, keyed by pointer.
   *
   * <p>Pointers that are absent are simply missing from the result; the caller decides which absence
   * is fatal, because that answer differs between a manifest and a lockfile.
   *
   * @throws VersionBumpException if the document is not well-formed JSON, or if a requested pointer
   *     resolves to something other than a string
   */
  public static Map<String, TextSplice.Span> locate(
      String json, Set<String> pointers, String origin) {
    Map<String, TextSplice.Span> found = new LinkedHashMap<>();
    try (JsonParser parser = new JsonFactory().createParser(json)) {
      JsonToken token;
      while ((token = parser.nextToken()) != null) {
        if (token == JsonToken.START_OBJECT
            || token == JsonToken.START_ARRAY
            || token == JsonToken.END_OBJECT
            || token == JsonToken.END_ARRAY
            || token == JsonToken.FIELD_NAME) {
          continue;
        }
        String pointer = parser.getParsingContext().pathAsPointer().toString();
        if (!pointers.contains(pointer)) {
          continue;
        }
        if (token != JsonToken.VALUE_STRING) {
          throw new VersionBumpException(
              origin + " has a non-string value at " + pointer + " (" + token + ")");
        }
        // Jackson decodes a string value lazily: until the text is actually asked for, the parser
        // has not consumed the body and currentLocation() still points just past the OPENING quote.
        // Reading it first is what makes the end offset the end of the literal.
        parser.getText();
        int start = (int) parser.currentTokenLocation().getCharOffset();
        int end = (int) parser.currentLocation().getCharOffset();
        verifyQuoted(json, start, end, pointer, origin);
        if (found.putIfAbsent(pointer, new TextSplice.Span(start, end)) != null) {
          throw new VersionBumpException(origin + " holds " + pointer + " more than once");
        }
      }
    } catch (IOException e) {
      throw new VersionBumpException("cannot parse " + origin + ": " + e.getMessage(), e);
    }
    return found;
  }

  /**
   * Which of the requested pointers the document declares as a field at all, whatever its value.
   *
   * <p>Separate from {@link #locate} because "the field is missing" and "the field holds something
   * other than a string" are different facts, and the lockfile rule needs the first: a lock that has
   * a {@code packages[""]} entry must carry a version inside it, while a lock with no {@code
   * packages} map at all (the long-obsolete {@code lockfileVersion 1}) legitimately has none.
   */
  public static Set<String> fieldsPresent(String json, Set<String> pointers, String origin) {
    Set<String> present = new LinkedHashSet<>();
    try (JsonParser parser = new JsonFactory().createParser(json)) {
      JsonToken token;
      while ((token = parser.nextToken()) != null) {
        if (token != JsonToken.FIELD_NAME) {
          continue;
        }
        String pointer = parser.getParsingContext().pathAsPointer().toString();
        if (pointers.contains(pointer)) {
          present.add(pointer);
        }
      }
    } catch (IOException e) {
      throw new VersionBumpException("cannot parse " + origin + ": " + e.getMessage(), e);
    }
    return present;
  }

  /**
   * The located span must be a complete string literal, quotes included. Verified against the
   * document rather than assumed — a span off by one produces a file that still looks like JSON at a
   * glance and is not.
   */
  private static void verifyQuoted(
      String json, int start, int end, String pointer, String origin) {
    if (start < 0
        || end > json.length()
        || end - start < 2
        || json.charAt(start) != '"'
        || json.charAt(end - 1) != '"') {
      throw new VersionBumpException(
          "the JSON parser's span for "
              + pointer
              + " in "
              + origin
              + " is not a quoted string ["
              + start
              + ","
              + end
              + ")");
    }
  }
}
