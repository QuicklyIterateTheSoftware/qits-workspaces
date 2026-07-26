package eu.wohlben.qits.workspaces.dto;

/**
 * A service's web-view configuration: which container port the proxy frames, the route the frame
 * opens at, and the optional extra base sub-path included in the served base. Presence on {@code
 * ServiceDefinitionDto} means the service is web-viewable ({@code port} is then always set).
 */
public record WebViewDto(Integer port, String entryPath, String basePath) {}
