package com.metaml.workbench.generation;

import java.time.Instant;

// Represents a running target platform instance with its engine port and resolved portal URL.
public record LaunchedProject(String projectId, String processKey, int port, Instant launchedAt, String modelId,
        String displayName, String targetPlatformUrl) {

    public LaunchedProject(String projectId, String processKey, int port, Instant launchedAt, String modelId,
            String displayName) {
        this(projectId, processKey, port, launchedAt, modelId, displayName, null);
    }

    public LaunchedProject(String projectId, String processKey, int port, Instant launchedAt, String modelId) {
        this(projectId, processKey, port, launchedAt, modelId, null, null);
    }
}
