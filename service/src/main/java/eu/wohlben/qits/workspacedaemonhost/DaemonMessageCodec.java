package eu.wohlben.qits.workspacedaemonhost;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.workspacedaemon.protocol.DaemonCodec;
import eu.wohlben.qits.workspacedaemon.protocol.DaemonMessage;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Map;

/**
 * The backend's bridge between a control-plane {@link DaemonMessage} and its JSON text frame. The
 * shared, framework-free {@link DaemonCodec} does the field mapping to/from a {@code Map}; this
 * class only bolts on Jackson (the same {@code ObjectMapper} the rest of {@code service} uses) so
 * the wire contract stays owned by {@code workspace-daemon-protocol}. The {@code workspace-daemon}
 * binary does the symmetric job with a Vert.x {@code JsonObject}.
 */
@ApplicationScoped
public class DaemonMessageCodec {

  @Inject ObjectMapper objectMapper;

  /** Serialize a message to the JSON text sent over the socket. */
  public String encode(DaemonMessage message) {
    try {
      return objectMapper.writeValueAsString(DaemonCodec.encode(message));
    } catch (Exception e) {
      throw new IllegalStateException("Failed to encode workspace-daemon message", e);
    }
  }

  /** Parse a received JSON text frame back into a message. */
  @SuppressWarnings("unchecked")
  public DaemonMessage decode(String json) {
    try {
      return DaemonCodec.decode(objectMapper.readValue(json, Map.class));
    } catch (Exception e) {
      throw new IllegalArgumentException("Failed to decode workspace-daemon message", e);
    }
  }
}
