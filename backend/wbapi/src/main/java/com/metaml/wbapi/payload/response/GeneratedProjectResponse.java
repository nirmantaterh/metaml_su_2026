package com.metaml.wbapi.payload.response;

import com.metaml.workbench.generation.GeneratedProject;

// DTO representation of GeneratedProject using String paths for JSON serialization.
public record GeneratedProjectResponse(String projectId, String directory, String processKey, String displayName) {

    public GeneratedProjectResponse(String projectId, String directory, String processKey) {
        this(projectId, directory, processKey, null);
    }

    public static GeneratedProjectResponse from(GeneratedProject project) {
        return new GeneratedProjectResponse(project.projectId(), project.directory().toString(),
                project.processKey(), project.displayName());
    }
}
