package com.metaml.workbench.generation;

import java.time.Instant;

// Public-facing info about a running generated app; deliberately carries no Process handle, so only
// SpringBootProjectLauncher can touch the process itself.
// modelId is filled in afterwards by WorkbenchServiceImpl - null means the project was launched
// before this backend session, so nothing links it back to a model.
public record LaunchedProject(String projectId, String processKey, int port, Instant launchedAt, String modelId, String displayName) {

    public LaunchedProject(String projectId, String processKey, int port, Instant launchedAt, String modelId) {
        this(projectId, processKey, port, launchedAt, modelId, null);
    }
}
