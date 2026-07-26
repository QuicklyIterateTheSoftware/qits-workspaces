package eu.wohlben.qits.workspaces.dto;

/**
 * One step of a workspace's bootstrap chain, declared in its committed {@code .qits-config.yml}
 * ({@code id} defaults to {@code name} when the file omits it). Replaces the DB-store {@code
 * BootstrapCommandDto} dropped in Part 5 (config-as-single-source-of-truth).
 */
public record BootstrapStepDto(String id, String name, String description) {}
