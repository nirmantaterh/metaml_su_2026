package com.metaml.workbench.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.camunda.bpm.engine.HistoryService;
import org.camunda.bpm.engine.RepositoryService;
import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.engine.ExternalTaskService;
import org.camunda.bpm.engine.TaskService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.metaml.workbench.bpmn.TwinModelGenerator;
import com.metaml.workbench.client.NodeManagerClient;
import com.metaml.workbench.codegen.DelegateClassGenerator;
import com.metaml.workbench.generation.SpringBootProjectGenerator;
import com.metaml.workbench.generation.SpringBootProjectLauncher;
import com.metaml.workbench.governance.ApprovalService;
import com.metaml.workbench.governance.PolicyDecisionEngine;
import com.metaml.workbench.model.TwinProcess;
import com.metaml.workbench.store.ProcessModelArchiveStore;
import com.metaml.workbench.store.ProcessModelFileStore;
import com.metaml.workbench.store.WorkbenchStateStore;
import com.metaml.workbench.workflow.WorkflowStateTracker;

class TwinProcessDiscoveryTest {

    private WorkbenchServiceImpl service;
    private TwinProcess twinA;
    private TwinProcess twinB;
    private TwinProcess twinC;

    @BeforeEach
    void setUp() {
        twinA = new TwinProcess();
        twinA.setId("twin-a-uuid");
        twinA.setModelId("model-x");
        twinA.setStatus("RUNNING");

        twinB = new TwinProcess();
        twinB.setId("twin-b-uuid");
        twinB.setModelId("model-x");
        twinB.setStatus("RUNNING");

        twinC = new TwinProcess();
        twinC.setId("twin-c-uuid");
        twinC.setModelId("model-y");
        twinC.setStatus("RUNNING");

        WorkbenchStateStore stateStore = mock(WorkbenchStateStore.class);
        when(stateStore.load()).thenReturn(new WorkbenchStateStore.Snapshot(List.of(twinA, twinB, twinC)));

        service = new WorkbenchServiceImpl(
                mock(NodeManagerClient.class),
                mock(GovernanceService.class),
                mock(PolicyDecisionEngine.class),
                mock(ApprovalService.class),
                mock(RuntimeService.class, org.mockito.Mockito.RETURNS_DEEP_STUBS),
                mock(RepositoryService.class, org.mockito.Mockito.RETURNS_DEEP_STUBS),
                mock(HistoryService.class),
                mock(TaskService.class),
                mock(ExternalTaskService.class),
                mock(TwinModelGenerator.class),
                stateStore,
                mock(ProcessModelFileStore.class),
                mock(ProcessModelArchiveStore.class),
                mock(DelegateClassGenerator.class),
                mock(SpringBootProjectGenerator.class),
                mock(SpringBootProjectLauncher.class),
                mock(WorkflowStateTracker.class));
        service.restoreState();
    }

    @Test
    void listTwinProcessesFiltersByModelIdAndSupportsMultipleTwins() {
        List<TwinProcess> modelXTwins = service.listTwinProcesses("model-x");

        assertThat(modelXTwins).hasSize(2);
        assertThat(modelXTwins).extracting(TwinProcess::getId).containsExactlyInAnyOrder("twin-a-uuid", "twin-b-uuid");
        assertThat(modelXTwins).extracting(TwinProcess::getModelId).containsOnly("model-x");
    }

    @Test
    void listTwinProcessesExcludesTwinsFromOtherModels() {
        List<TwinProcess> modelYTwins = service.listTwinProcesses("model-y");

        assertThat(modelYTwins).hasSize(1);
        assertThat(modelYTwins.get(0).getId()).isEqualTo("twin-c-uuid");
    }

    @Test
    void listTwinProcessesReturnsEmptyListWhenModelHasNoTwins() {
        List<TwinProcess> unknownTwins = service.listTwinProcesses("model-unknown");

        assertThat(unknownTwins).isEmpty();
    }

    @Test
    void listTwinProcessesThrowsIllegalArgumentExceptionOnNullOrBlankModelId() {
        assertThatThrownBy(() -> service.listTwinProcesses(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("modelId must not be blank");

        assertThatThrownBy(() -> service.listTwinProcesses("   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("modelId must not be blank");
    }
}
