package eu.wohlben.qits.workspaces.control;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * The workspace sidecar written beside the bare origin by {@link WorkspaceMetadataStore}.
 *
 * <p>Registered for reflection because {@code WorkspaceMetadataStore} serializes it with an
 * <strong>injected {@code ObjectMapper}</strong> rather than through JAX-RS. Quarkus registers the
 * types it can see on resource method signatures; a type only ever reached by a direct {@code
 * writeValue}/{@code readValue} call is invisible to that, so native-image discards its members and
 * Jackson fails at runtime with "No serializer found ... and no properties discovered". That is
 * exactly what the first working binary did on the first workspace create — a 500 on the primary
 * write path, while {@code mvn verify} stayed green, because on the JVM reflection needs no
 * registration. Any new type that crosses a bare {@code ObjectMapper} in this repo needs the same
 * annotation.
 */
@RegisterForReflection
public class WorkspaceMetadata {
  public String workspaceId;
  public String parent;
}
