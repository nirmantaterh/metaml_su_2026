package com.metaml.workbench.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.camunda.bpm.engine.ExternalTaskService;
import org.camunda.bpm.engine.HistoryService;
import org.camunda.bpm.engine.RepositoryService;
import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.engine.TaskService;
import org.camunda.bpm.engine.history.HistoricActivityInstance;
import org.camunda.bpm.engine.history.HistoricActivityInstanceQuery;
import org.camunda.bpm.engine.runtime.ActivityInstance;
import org.camunda.bpm.engine.runtime.ProcessInstanceQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.metaml.workbench.bpmn.TwinModelGenerator;
import com.metaml.workbench.client.AgentAvailabilityResult;
import com.metaml.workbench.client.NodeManagerClient;
import com.metaml.workbench.codegen.DelegateClassGenerator;
import com.metaml.workbench.generation.SpringBootProjectGenerator;
import com.metaml.workbench.generation.SpringBootProjectLauncher;
import com.metaml.workbench.governance.ApprovalService;
import com.metaml.workbench.governance.PolicyDecisionEngine;
import com.metaml.workbench.model.ActivityLink;
import com.metaml.workbench.model.AgentDecision;
import com.metaml.workbench.model.GovernanceDecision;
import com.metaml.workbench.model.TwinProcess;
import com.metaml.workbench.store.ProcessModelArchiveStore;
import com.metaml.workbench.store.ProcessModelFileStore;
import com.metaml.workbench.store.WorkbenchStateStore;
import com.metaml.workbench.workflow.WorkflowEventStore;
import com.metaml.workbench.workflow.WorkflowStateTracker;

// Lifecycle guard: verifies that evolveActivity() rejects evolution when the original
// process instance has ended, while still allowing evolution on running processes.
class EvolutionLifecycleGuardTest {

    @TempDir
    Path tempDir;

    private static final String TWIN_ID = "twin-lifecycle-01";
    private static final String ORIGINAL_PROCESS_ID = "orig-proc-123";
    private static final String TWIN_PROCESS_ID = "twin-proc-456";
    private static final String ACTIVITY_ID = "Task_KYC";
    private static final String TWIN_ACTIVITY_ID = "Task_KYC_automate";
    private static final String VISIT_ID = "visit-789";

    private RuntimeService runtimeService;
    private HistoryService historyService;
    private GovernanceService governanceService;
    private NodeManagerClient nodeManagerClient;
    private WorkbenchServiceImpl service;

    @BeforeEach
    void setUp() throws Exception {
        runtimeService = mock(RuntimeService.class, RETURNS_DEEP_STUBS);
        historyService = mock(HistoryService.class, RETURNS_DEEP_STUBS);
        governanceService = mock(GovernanceService.class);
        nodeManagerClient = mock(NodeManagerClient.class);

        Path eventFile = tempDir.resolve("events.json");
        Path modelsDir = tempDir.resolve("models");
        Files.createDirectories(modelsDir);
        Path templateDir = tempDir.resolve("template");
        Files.createDirectories(templateDir);
        Files.writeString(templateDir.resolve("pom.xml"), "<project>fake</project>");
        Path outputDir = tempDir.resolve("generated-projects");

        WorkflowEventStore eventStore = new WorkflowEventStore(eventFile.toString(), true);
        WorkflowStateTracker tracker = new WorkflowStateTracker(eventStore);
        invokeDeclared(tracker, WorkflowStateTracker.class, "restore");

        WorkbenchStateStore stateStore = mock(WorkbenchStateStore.class);
        ApprovalService approvalService = mock(ApprovalService.class);
        when(stateStore.load()).thenReturn(new WorkbenchStateStore.Snapshot(List.of()));
        when(approvalService.listAllApproved()).thenReturn(List.of());

        ProcessModelArchiveStore archiveStore = mock(ProcessModelArchiveStore.class);
        when(archiveStore.findAll()).thenReturn(List.of());

        RepositoryService repositoryService = mock(RepositoryService.class, RETURNS_DEEP_STUBS);
        DelegateClassGenerator delegateClassGenerator = mock(DelegateClassGenerator.class);
        when(delegateClassGenerator.generate(anyString(), anyString())).thenReturn(List.of());

        SpringBootProjectGenerator generator = new SpringBootProjectGenerator(templateDir.toString(),
                outputDir.toString(), new TwinModelGenerator(), new DelegateClassGenerator(),
                new com.metaml.workbench.codegen.ExternalTaskWorkerGenerator());

        service = new WorkbenchServiceImpl(nodeManagerClient, governanceService,
                mock(PolicyDecisionEngine.class), approvalService, runtimeService, repositoryService,
                historyService, mock(TaskService.class), mock(ExternalTaskService.class),
                mock(TwinModelGenerator.class), stateStore,
                new ProcessModelFileStore(modelsDir.toString()), archiveStore,
                delegateClassGenerator, generator, new SpringBootProjectLauncher(), tracker);

        // Inject a TwinProcess with proper activity links into the twinProcesses map
        TwinProcess twin = new TwinProcess();
        twin.setId(TWIN_ID);
        twin.setOriginalProcessId(ORIGINAL_PROCESS_ID);
        twin.setTwinProcessId(TWIN_PROCESS_ID);
        twin.setStatus("RUNNING");
        twin.setEventLog(new CopyOnWriteArrayList<>());
        twin.setActivityLinks(new CopyOnWriteArrayList<>(List.of(
                new ActivityLink(ACTIVITY_ID, TWIN_ACTIVITY_ID))));

        @SuppressWarnings("unchecked")
        Map<String, TwinProcess> twinProcesses = (Map<String, TwinProcess>) getField(service, "twinProcesses");
        twinProcesses.put(TWIN_ID, twin);
    }

    @Test
    void runningOriginalProcessAllowsEvolution() {
        // Original process IS running
        stubOriginalProcessRunning(true);
        // History returns a current (active) visit
        stubHistoryVisit(VISIT_ID, /* endTime */ null);
        // Runtime activity tree returns an active visit (for loopCounter resolution)
        stubRuntimeActivityTree(VISIT_ID);
        // Governance allows
        when(governanceService.reserveEvolutionSlot(anyString(), anyString()))
                .thenReturn(new GovernanceDecision(true, null));
        // Node Manager resolves
        AgentAvailabilityResult available = new AgentAvailabilityResult();
        available.setAvailable(true);
        available.setAgentName("validator-agent-01");
        available.setReason("Available");
        when(nodeManagerClient.checkAgentAvailability("validator")).thenReturn(available);

        AgentDecision decision = service.evolveActivity(TWIN_ID, ACTIVITY_ID, "validator");

        assertThat(decision.isApproved()).isTrue();
        assertThat(decision.getAgentName()).isEqualTo("validator-agent-01");
        // Verify the evolved agent variable was set on the twin
        verify(runtimeService).setVariable(TWIN_PROCESS_ID, "evolvedAgent_" + TWIN_ACTIVITY_ID,
                "validator-agent-01");
    }

    @Test
    void endedOriginalProcessRejectsEvolution() {
        // Original process has ENDED
        stubOriginalProcessRunning(false);
        // History returns a completed (historical) visit — the fallback path
        stubHistoryVisit(VISIT_ID, java.time.Instant.now());

        AgentDecision decision = service.evolveActivity(TWIN_ID, ACTIVITY_ID, "validator");

        assertThat(decision.isApproved()).isFalse();
        assertThat(decision.getReason()).contains("Original process instance has ended");
        assertThat(decision.getReason()).contains("activity cannot be evolved");
        // Verify NO evolved agent variable was set
        verify(runtimeService, never()).setVariable(anyString(), anyString(), any());
        // Verify governance was NOT invoked
        verify(governanceService, never()).reserveEvolutionSlot(anyString(), anyString());
        // Verify node manager was NOT contacted
        verify(nodeManagerClient, never()).checkAgentAvailability(anyString());
    }

    @Test
    void historicalVisitOnEndedProcessDoesNotMasqueradeAsActive() {
        // Original process has ENDED
        stubOriginalProcessRunning(false);
        // History returns ONLY completed visits (all with endTime) — the fallback in
        // currentVisitId returns visits.get(0), which is the completed historical visit
        HistoricActivityInstance completedVisit = mock(HistoricActivityInstance.class);
        when(completedVisit.getId()).thenReturn(VISIT_ID);
        when(completedVisit.getEndTime()).thenReturn(java.util.Date.from(java.time.Instant.now().minusSeconds(3600)));

        HistoricActivityInstanceQuery query = mock(HistoricActivityInstanceQuery.class, RETURNS_DEEP_STUBS);
        when(historyService.createHistoricActivityInstanceQuery()).thenReturn(query);
        when(query.processInstanceId(ORIGINAL_PROCESS_ID)).thenReturn(query);
        when(query.activityId(ACTIVITY_ID)).thenReturn(query);
        when(query.orderByHistoricActivityInstanceStartTime()).thenReturn(query);
        when(query.desc()).thenReturn(query);
        when(query.list()).thenReturn(List.of(completedVisit));

        AgentDecision decision = service.evolveActivity(TWIN_ID, ACTIVITY_ID, "validator");

        assertThat(decision.isApproved()).isFalse();
        assertThat(decision.getReason()).contains("Original process instance has ended");
        // The key invariant: even though currentVisitId() returned a non-null visitId
        // (the historical fallback), the lifecycle guard blocked evolution
        verify(runtimeService, never()).setVariable(anyString(), anyString(), any());
    }

    @Test
    void bridgeIsNotBlockedByLifecycleGuard() {
        // Original process has ENDED
        stubOriginalProcessRunning(false);
        // History returns a completed visit
        stubHistoryVisit(VISIT_ID, java.time.Instant.now());
        // loopCounterOf calls getActivityInstance — null for an ended process
        when(runtimeService.getActivityInstance(ORIGINAL_PROCESS_ID)).thenReturn(null);
        // Governance allows (bridge reaches runEvolution if alreadyEvolved is false)
        when(governanceService.reserveEvolutionSlot(anyString(), anyString()))
                .thenReturn(new GovernanceDecision(true, null));
        // Node Manager resolves (bridge will attempt evolution with DEFAULT_BRIDGE_AGENT_TYPE)
        AgentAvailabilityResult available = new AgentAvailabilityResult();
        available.setAvailable(true);
        available.setAgentName("validator-agent-01");
        available.setReason("Available");
        when(nodeManagerClient.checkAgentAvailability("validator")).thenReturn(available);

        // Bridge calls bridgeActivityEvent, NOT evolveActivity.
        // The lifecycle guard is scoped to evolveOnce() only, so bridge should
        // still reach its own alreadyEvolved check and return its decision.
        AgentDecision decision = service.bridgeActivityEvent(TWIN_ID, ACTIVITY_ID);

        // Bridge should NOT get the lifecycle-guard rejection message
        if (decision.getReason() != null) {
            assertThat(decision.getReason()).doesNotContain("activity cannot be evolved");
        }
    }

    @Test
    void eventLogRecordsEndedProcessRejection() {
        stubOriginalProcessRunning(false);
        stubHistoryVisit(VISIT_ID, java.time.Instant.now());

        service.evolveActivity(TWIN_ID, ACTIVITY_ID, "validator");

        TwinProcess twin = service.getTwinProcess(TWIN_ID);
        assertThat(twin.getEventLog()).anyMatch(entry ->
                entry.contains("Evolution blocked") && entry.contains("has already ended"));
    }

    private void stubOriginalProcessRunning(boolean running) {
        ProcessInstanceQuery piQuery = mock(ProcessInstanceQuery.class);
        when(runtimeService.createProcessInstanceQuery()).thenReturn(piQuery);
        when(piQuery.processInstanceId(ORIGINAL_PROCESS_ID)).thenReturn(piQuery);
        when(piQuery.singleResult()).thenReturn(running ? mock(org.camunda.bpm.engine.runtime.ProcessInstance.class) : null);

        // getTwinProcess calls computeStatus which checks BOTH original and twin
        ProcessInstanceQuery twinPiQuery = mock(ProcessInstanceQuery.class);
        when(piQuery.processInstanceId(TWIN_PROCESS_ID)).thenReturn(twinPiQuery);
        when(twinPiQuery.singleResult()).thenReturn(running ? mock(org.camunda.bpm.engine.runtime.ProcessInstance.class) : null);
    }

    private void stubHistoryVisit(String visitId, java.time.Instant endTime) {
        HistoricActivityInstance visit = mock(HistoricActivityInstance.class);
        when(visit.getId()).thenReturn(visitId);
        when(visit.getEndTime()).thenReturn(endTime == null ? null : java.util.Date.from(endTime));

        HistoricActivityInstanceQuery query = mock(HistoricActivityInstanceQuery.class, RETURNS_DEEP_STUBS);
        when(historyService.createHistoricActivityInstanceQuery()).thenReturn(query);
        when(query.processInstanceId(ORIGINAL_PROCESS_ID)).thenReturn(query);
        when(query.activityId(ACTIVITY_ID)).thenReturn(query);
        when(query.orderByHistoricActivityInstanceStartTime()).thenReturn(query);
        when(query.desc()).thenReturn(query);
        when(query.asc()).thenReturn(query);
        when(query.list()).thenReturn(List.of(visit));
    }

    private void stubRuntimeActivityTree(String visitId) {
        ActivityInstance tree = mock(ActivityInstance.class);
        ActivityInstance activityVisit = mock(ActivityInstance.class);
        when(activityVisit.getId()).thenReturn(visitId);
        when(activityVisit.getExecutionIds()).thenReturn(new String[]{"exec-1"});
        when(tree.getActivityInstances(ACTIVITY_ID)).thenReturn(new ActivityInstance[]{activityVisit});
        when(runtimeService.getActivityInstance(ORIGINAL_PROCESS_ID)).thenReturn(tree);
    }

    private static void invokeDeclared(Object target, Class<?> type, String methodName) {
        try {
            java.lang.reflect.Method method = type.getDeclaredMethod(methodName);
            method.setAccessible(true);
            method.invoke(target);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Object getField(Object target, String fieldName) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(target);
    }
}
