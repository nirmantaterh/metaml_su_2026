package com.metaml.workbench.model;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
public class ProcessModel {
    private String id;
    private String name;
    // Process documentation and metadata are embedded directly inside bpmnXml.
    private String bpmnXml;
    private Instant createdAt;
    private String processDefinitionId;
    // Tenant ownership identifier.
    private String tenantId;
    // Optional authored twin BPMN XML.
    private String authoredTwinBpmnXml;
    // Persisted authored-model correspondence. This is distinct from per-run TwinProcess.activityLinks.
    private List<ProxyTwinActivityMapping> proxyTwinActivityMappings = new ArrayList<>();

    public ProcessModel(String id, String name, String bpmnXml, Instant createdAt, String processDefinitionId,
            String tenantId) {
        this(id, name, bpmnXml, null, createdAt, processDefinitionId, tenantId);
    }

    public ProcessModel(String id, String name, String bpmnXml, String authoredTwinBpmnXml, Instant createdAt,
            String processDefinitionId, String tenantId) {
        this(id, name, bpmnXml, authoredTwinBpmnXml, List.of(), createdAt, processDefinitionId, tenantId);
    }

    public ProcessModel(String id, String name, String bpmnXml, String authoredTwinBpmnXml,
            List<ProxyTwinActivityMapping> proxyTwinActivityMappings, Instant createdAt,
            String processDefinitionId, String tenantId) {
        this.id = id;
        this.name = name;
        this.bpmnXml = bpmnXml;
        this.authoredTwinBpmnXml = authoredTwinBpmnXml;
        this.proxyTwinActivityMappings = proxyTwinActivityMappings == null
                ? new ArrayList<>() : new ArrayList<>(proxyTwinActivityMappings);
        this.createdAt = createdAt;
        this.processDefinitionId = processDefinitionId;
        this.tenantId = tenantId;
    }

    // Constructor for process models without a tenant.
    public ProcessModel(String id, String name, String bpmnXml, Instant createdAt, String processDefinitionId) {
        this(id, name, bpmnXml, createdAt, processDefinitionId, null);
    }

    public boolean hasAuthoredTwin() {
        return authoredTwinBpmnXml != null && !authoredTwinBpmnXml.isBlank();
    }
}
