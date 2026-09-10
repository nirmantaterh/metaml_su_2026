package com.metaml.workbench.generation;

import java.time.Instant;

// Represents a running target platform instance with dynamic port, process key, and base URL.
public record LaunchedProject(String projectId, String processKey, int port, Instant launchedAt, String modelId, String displayName) {

    public LaunchedProject(String projectId, String processKey, int port, Instant launchedAt, String modelId) {
        this(projectId, processKey, port, launchedAt, modelId, null);
    }
}
