package eu.wohlben.qits.workspaces.control;

import eu.wohlben.qits.workspaces.error.VersionBumpException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * Locates the version-bearing elements of one {@code pom.xml} as character spans into its own text,
 * using StAX for structure only. Nothing here parses a version, resolves a property, or writes a
 * file — it answers "which characters of this exact document are a version, and whose".
 *
 * <h2>Why the offsets come from line/column and not from {@code getCharacterOffset()}</h2>
 *
 * Measured against all 45 poms in this platform, on the JDK's own StAX implementation:
 *
 * <ul>
 *   <li>{@code Location.getCharacterOffset()} is exact only within the scanner's first 8192-character
 *       buffer. Past the first refill it goes wrong — 231 of 2857 start elements landed mid-token,
 *       and every real service pom here is larger than one buffer. Trusting it would produce a
 *       corrupt pom on the big files and a correct one on the small ones, which is the worst
 *       possible failure shape.
 *   <li>{@code getLineNumber()}/{@code getColumnNumber()} are maintained across refills and were
 *       <b>exact for all 2857</b> start elements, up to a 19 KB document, with LF and CRLF alike.
 * </ul>
 *
 * So the offset is computed here: our own line index over the original text, plus the reported
 * column. Every located offset is then re-verified against the document ({@code the character before
 * it must be the '>' that closes the start tag}), and the value's end is found by scanning forward
 * to the next {@code '<'} — which, since raw {@code '<'} is illegal in XML character data, is
 * necessarily the start of the closing tag. The parser supplies structure; the original text
 * supplies every byte offset.
 *
 * <h2>{@code newDefaultFactory()}, not {@code newInstance()}</h2>
 *
 * The offset behaviour above is a property of the JDK implementation. {@code newInstance()} runs a
 * {@code ServiceLoader} lookup and would hand this code whatever StAX implementation a future
 * dependency happens to drop on the classpath, silently changing the semantics this class is
 * measured against. {@code newDefaultFactory()} always returns the JDK's own, needs no reflective
 * lookup, and therefore also needs no native-image registration.
 */
public final class PomVersions {

  private PomVersions() {}

  /** One located element: the exact characters between its tags, and the text they spell. */
  public record Element(TextSplice.Span span, String value) {}

  /** A {@code <dependency>} entry, with its version element when it declares one. */
  public record Dependency(String groupId, String artifactId, Element version) {}

  /**
   * Everything one pom says about versions and about the reactor it belongs to.
   *
   * @param groupId the effective group — the pom's own if it declares one, else its parent's
   * @param artifactId the pom's own artifactId
   * @param version {@code /project/version}, or null when the pom inherits it
   * @param parentVersion {@code /project/parent/version}, or null when the pom has no parent
   * @param parentGroupId the declared parent group, or null
   * @param parentArtifactId the declared parent artifact, or null
   * @param modules {@code /project/modules/module}, in document order
   * @param dependencies every {@code <dependency>} anywhere in the document, including inside
   *     {@code <dependencyManagement>} and inside profiles
   */
  public record Scan(
      String groupId,
      String artifactId,
      Element version,
      Element parentVersion,
      String parentGroupId,
      String parentArtifactId,
      List<String> modules,
      List<Dependency> dependencies) {}

  public static Scan scan(String xml, String origin) {
    int[] lineStarts = lineStarts(xml);

    String ownGroupId = null;
    String artifactId = null;
    Element version = null;
    Element parentVersion = null;
    String parentGroupId = null;
    String parentArtifactId = null;
    List<String> modules = new ArrayList<>();
    List<Dependency> dependencies = new ArrayList<>();

    String depGroupId = null;
    String depArtifactId = null;
    Element depVersion = null;
    boolean inDependency = false;

    List<String> path = new ArrayList<>();
    XMLStreamReader reader = open(xml, origin);
    try {
      while (reader.hasNext()) {
        int event = reader.next();
        if (event == XMLStreamConstants.END_ELEMENT) {
          String closing = pop(path, origin);
          if (closing.equals("dependency") && inDependency) {
            dependencies.add(new Dependency(depGroupId, depArtifactId, depVersion));
            inDependency = false;
            depGroupId = null;
            depArtifactId = null;
            depVersion = null;
          }
          continue;
        }
        if (event != XMLStreamConstants.START_ELEMENT) {
          continue;
        }

        String name = reader.getLocalName();
        String parent = path.isEmpty() ? "" : path.get(path.size() - 1);
        String grandparent = path.size() < 2 ? "" : path.get(path.size() - 2);
        path.add(name);

        if (name.equals("dependency")) {
          if (inDependency) {
            throw new VersionBumpException("nested <dependency> in " + origin);
          }
          inDependency = true;
          continue;
        }

        boolean atProject = parent.equals("project") && path.size() == 2;
        boolean atParent = parent.equals("parent") && grandparent.equals("project");
        boolean atModule =
            name.equals("module") && parent.equals("modules") && grandparent.equals("project");
        boolean atDependencyField = inDependency && parent.equals("dependency");

        if (atProject && name.equals("groupId")) {
          ownGroupId = text(reader, xml, lineStarts, origin, name).value().trim();
        } else if (atProject && name.equals("artifactId")) {
          artifactId = text(reader, xml, lineStarts, origin, name).value().trim();
        } else if (atProject && name.equals("version")) {
          version = text(reader, xml, lineStarts, origin, name);
        } else if (atParent && name.equals("groupId")) {
          parentGroupId = text(reader, xml, lineStarts, origin, name).value().trim();
        } else if (atParent && name.equals("artifactId")) {
          parentArtifactId = text(reader, xml, lineStarts, origin, name).value().trim();
        } else if (atParent && name.equals("version")) {
          parentVersion = text(reader, xml, lineStarts, origin, name);
        } else if (atModule) {
          modules.add(text(reader, xml, lineStarts, origin, name).value().trim());
        } else if (atDependencyField && name.equals("groupId")) {
          depGroupId = text(reader, xml, lineStarts, origin, name).value().trim();
        } else if (atDependencyField && name.equals("artifactId")) {
          depArtifactId = text(reader, xml, lineStarts, origin, name).value().trim();
        } else if (atDependencyField && name.equals("version")) {
          depVersion = text(reader, xml, lineStarts, origin, name);
        } else {
          continue;
        }
        // Every branch above consumed the element's END_ELEMENT via getElementText().
        pop(path, origin);
      }
    } catch (XMLStreamException e) {
      throw new VersionBumpException("cannot parse " + origin + ": " + e.getMessage(), e);
    } finally {
      close(reader);
    }

    if (artifactId == null) {
      throw new VersionBumpException(origin + " declares no <artifactId>");
    }
    String effectiveGroupId = ownGroupId != null ? ownGroupId : parentGroupId;
    return new Scan(
        effectiveGroupId,
        artifactId,
        version,
        parentVersion,
        parentGroupId,
        parentArtifactId,
        List.copyOf(modules),
        List.copyOf(dependencies));
  }

  private static XMLStreamReader open(String xml, String origin) {
    XMLInputFactory factory = XMLInputFactory.newDefaultFactory();
    // A pom needs neither, and a bump must never fetch anything over the network to read a file.
    factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
    factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
    try {
      return factory.createXMLStreamReader(new StringReader(xml));
    } catch (XMLStreamException e) {
      throw new VersionBumpException("cannot parse " + origin + ": " + e.getMessage(), e);
    }
  }

  private static void close(XMLStreamReader reader) {
    try {
      reader.close();
    } catch (XMLStreamException ignored) {
      // Closing a reader over an in-memory string releases nothing that matters.
    }
  }

  private static String pop(List<String> path, String origin) {
    if (path.isEmpty()) {
      throw new VersionBumpException("unbalanced elements in " + origin);
    }
    return path.remove(path.size() - 1);
  }

  /**
   * Read the text of the element the reader has just started, and the exact span it occupies in the
   * original document. Consumes the element's {@code END_ELEMENT}.
   */
  private static Element text(
      XMLStreamReader reader, String xml, int[] lineStarts, String origin, String name)
      throws XMLStreamException {
    int start = offsetAfterStartTag(reader, xml, lineStarts, origin, name);
    reader.getElementText();

    int end = xml.indexOf('<', start);
    if (end < 0) {
      throw new VersionBumpException(
          "no closing tag for <" + name + "> at offset " + start + " in " + origin);
    }
    if (!closesAt(xml, end, name)) {
      throw new VersionBumpException(
          "<"
              + name
              + "> at offset "
              + start
              + " in "
              + origin
              + " does not hold a plain text value; the bump splices text spans and will not guess"
              + " at markup inside a version element");
    }
    return new Element(new TextSplice.Span(start, end), xml.substring(start, end));
  }

  /**
   * The reported line/column of a {@code START_ELEMENT} is the position immediately after the
   * {@code '>'} that closes the start tag. Verified against the document rather than assumed: a
   * StAX implementation that reported something else would otherwise splice silently wrong bytes.
   */
  private static int offsetAfterStartTag(
      XMLStreamReader reader, String xml, int[] lineStarts, String origin, String name) {
    int line = reader.getLocation().getLineNumber();
    int column = reader.getLocation().getColumnNumber();
    if (line < 1 || line > lineStarts.length || column < 1) {
      throw new VersionBumpException(
          "the XML parser reported no usable location for <"
              + name
              + "> in "
              + origin
              + " (line "
              + line
              + ", column "
              + column
              + ")");
    }
    int offset = lineStarts[line - 1] + column - 1;
    if (offset < 1 || offset > xml.length() || xml.charAt(offset - 1) != '>') {
      throw new VersionBumpException(
          "the XML parser's location for <"
              + name
              + "> in "
              + origin
              + " does not land after a start tag (line "
              + line
              + ", column "
              + column
              + ", offset "
              + offset
              + ")");
    }
    return offset;
  }

  /** True when {@code xml} holds {@code </name>} — whitespace before the {@code >} is legal — at {@code at}. */
  private static boolean closesAt(String xml, int at, String name) {
    int i = at;
    if (!xml.startsWith("</", i)) {
      return false;
    }
    i += 2;
    if (!xml.startsWith(name, i)) {
      return false;
    }
    i += name.length();
    while (i < xml.length() && Character.isWhitespace(xml.charAt(i))) {
      i++;
    }
    return i < xml.length() && xml.charAt(i) == '>';
  }

  /**
   * The index at which each line of {@code text} begins. All three XML line terminators are counted,
   * so the mapping matches whatever the parser saw.
   */
  static int[] lineStarts(String text) {
    List<Integer> starts = new ArrayList<>();
    starts.add(0);
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c == '\n') {
        starts.add(i + 1);
      } else if (c == '\r') {
        if (i + 1 < text.length() && text.charAt(i + 1) == '\n') {
          i++;
        }
        starts.add(i + 1);
      }
    }
    int[] out = new int[starts.size()];
    for (int i = 0; i < out.length; i++) {
      out[i] = starts.get(i);
    }
    return out;
  }
}
