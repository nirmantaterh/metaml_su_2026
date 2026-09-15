package com.metaml.workbench.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.hibernate.annotations.CreationTimestamp;

@Entity
@Table(name = "process_model_archives")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ProcessModelArchive {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // ProcessModel string identifier key.
    @Column(nullable = false, unique = true)
    private String modelId;

    @Column(nullable = false)
    private String name;

    @Lob
    private String bpmnXml;

    @Column(length = 255)
    private String bpmnFilePath;

    // Optional independently authored twin BPMN XML.
    @Lob
    private String twinBpmnXml;

    @Column(length = 255)
    private String twinBpmnFilePath;

    @ElementCollection
    @CollectionTable(name = "process_model_activity_mappings", joinColumns = @JoinColumn(name = "archive_id"))
    @OrderColumn(name = "mapping_order")
    private List<ProxyTwinActivityMapping> proxyTwinActivityMappings = new ArrayList<>();

    @CreationTimestamp
    private LocalDateTime createdAt;

    private String processDefinitionId;

    private String tenantId;

    private Integer major;
    private Integer minor;
    private Integer patch;
    private String preRelease;
    private String buildMetadata;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;
}
