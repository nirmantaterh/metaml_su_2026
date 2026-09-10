package com.metaml.workbench.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.camunda.bpm.engine.ProcessEngine;
import org.camunda.bpm.engine.ProcessEngineConfiguration;
import org.camunda.bpm.engine.RepositoryService;
import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.camunda.bpm.engine.delegate.JavaDelegate;
import org.camunda.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.camunda.bpm.model.bpmn.Bpmn;
import org.camunda.bpm.model.bpmn.BpmnModelInstance;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.metaml.workbench.automation.DefaultProjectAutomationService;
import com.metaml.workbench.automation.ProjectAutomationService;
import com.metaml.workbench.bpmn.TwinModelGenerator;
import com.metaml.workbench.capability.binding.CapabilityBindingRegistry;
import com.metaml.workbench.capability.binding.CapabilityBindingStore;
import com.metaml.workbench.capability.runtime.CapabilityBinding;
import com.metaml.workbench.client.AgentAvailabilityResult;
import com.metaml.workbench.client.NodeManagerClient;
import com.metaml.workbench.codegen.DelegateClassGenerator;
import com.metaml.workbench.codegen.ExternalTaskWorkerGenerator;
import com.metaml.workbench.generation.SpringBootProjectGenerator;
import com.metaml.workbench.generation.SpringBootProjectLauncher;
import com.metaml.workbench.governance.ApprovalService;
import com.metaml.workbench.governance.ApprovalStore;
import com.metaml.workbench.governance.TenantPolicyService;
import com.metaml.workbench.governance.TenantPolicyStore;
import com.metaml.workbench.governance.PolicyDecisionEngine;
import com.metaml.workbench.model.AgentDecision;
import com.metaml.workbench.model.AgentVariables;
import com.metaml.workbench.model.GovernanceDecision;
import com.metaml.workbench.model.ProcessModel;
import com.metaml.workbench.model.TwinProcess;
import com.metaml.workbench.store.ProcessModelArchiveStore;
import com.metaml.workbench.store.ProcessModelFileStore;
import com.metaml.workbench.store.WorkbenchStateStore;
import com.metaml.workbench.workflow.WorkflowEventStore;
import com.metaml.workbench.workflow.WorkflowStateTracker;

// P7 Step 5: real-Camunda proof that a durable capability-binding persistence failure fails the
// evolution operation itself and restores the exact prior evolvedAgent_*/evolvedAgentType_* state -
// never a blind removal, which would destroy a still-valid previous binding on a rebind failure. Same
// hand-wired in-memory-engine harness pattern as CapabilityGapRuntimeLifecycleEndToEndTest. A
// synthetic, independently invented domain - no RedCollar.
@Tag("slow")
class CapabilityBindingCompensationEndToEndTest {

    @TempDir
    Path tempDir;

    private static final String ACTIVITY = "Activity_Under_Test";

    private static BpmnModelInstance genericProcess() {
        return Bpmn.readModelFromStream(new java.io.ByteArrayInputStream(("""
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                                   id="Definitions_BindingCompensation" targetNamespace="http://metaml.com/test">
                  <bpmn:process id="Process_BindingCompensation" name="Generic Binding Compensation"
                      isExecutable="true">
                    <bpmn:startEvent id="Start"><bpmn:outgoing>F1</bpmn:outgoing></bpmn:startEvent>
                    <bpmn:userTask id="%s" name="Under Test">
                      <bpmn:incoming>F1</bpmn:incoming><bpmn:outgoing>F2</bpmn:outgoing>
                    </bpmn:userTask>
                    <bpmn:endEvent id="End"><bpmn:incoming>F2</bpmn:incoming></bpmn:endEvent>
                    <bpmn:sequenceFlow id="F1" sourceRef="Start" targetRef="%s" />
                    <bpmn:sequenceFlow id="F2" sourceRef="%s" targetRef="End" />
                  </bpmn:process>
                </bpmn:definitions>
                """.formatted(ACTIVITY, ACTIVITY, ACTIVITY))
                .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    private ProcessEngine engine;
    private RuntimeService runtimeService;
    private NodeManagerClient nodeManagerClient;
    private WorkbenchServiceImpl service;
    private Path bindingFile;
    private String twinId;

    @BeforeEach
    void setUp() throws Exception {
        java.util.concurrent.atomic.AtomicReference<JavaDelegate> realDelegate =
                new java.util.concurrent.atomic.AtomicReference<>();
        JavaDelegate delegateBridge = execution -> realDelegate.get().execute(execution);

        ProcessEngineConfiguration config = ProcessEngineConfiguration
                .createStandaloneInMemProcessEngineConfiguration();
        config.setJdbcUrl("jdbc:h2:mem:binding-compensation-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        config.setJdbcDriver("org.h2.Driver");
        config.setJdbcUsername("sa");
        config.setJdbcPassword("");
        config.setDatabaseSchemaUpdate(ProcessEngineConfiguration.DB_SCHEMA_UPDATE_TRUE);
        config.setJobExecutorActivate(false);
        config.setHistory(ProcessEngineConfiguration.HISTORY_FULL);
        if (config instanceof ProcessEngineConfigurationImpl configImpl) {
            configImpl.setHistoryTimeToLive("180");
            configImpl.setBeans(Map.of("twinAutomationDelegate", delegateBridge));
        }
        engine = config.buildProcessEngine();
        runtimeService = engine.getRuntimeService();
        RepositoryService repositoryService = engine.getRepositoryService();

        nodeManagerClient = mock(NodeManagerClient.class);

        GovernanceService governanceService = mock(GovernanceService.class);
        when(governanceService.reserveEvolutionSlot(anyString(), anyString()))
                .thenReturn(new GovernanceDecision(true, null));
        when(governanceService.reserveTwinExecutionSlot(anyString()))
                .thenReturn(new GovernanceDecision(true, null));

        WorkflowEventStore eventStore = new WorkflowEventStore(tempDir.resolve("events.json").toString(), true);
        WorkflowStateTracker tracker = new WorkflowStateTracker(eventStore);

        WorkbenchStateStore stateStore = mock(WorkbenchStateStore.class);
        when(stateStore.load()).thenReturn(new WorkbenchStateStore.Snapshot(List.of()));
        ProcessModelArchiveStore archiveStore = mock(ProcessModelArchiveStore.class);
        when(archiveStore.findAll()).thenReturn(List.of());
        DelegateClassGenerator delegateClassGenerator = mock(DelegateClassGenerator.class);
        when(delegateClassGenerator.generate(anyString(), anyString())).thenReturn(List.of());

        Path templateDir = tempDir.resolve("template");
        Files.createDirectories(templateDir);
        Files.writeString(templateDir.resolve("pom.xml"), "<project>fake</project>");
        SpringBootProjectGenerator generator = new SpringBootProjectGenerator(templateDir.toString(),
                tempDir.resolve("generated-projects").toString(), new TwinModelGenerator(),
                new DelegateClassGenerator(), new ExternalTaskWorkerGenerator());

        ApprovalStore approvalStore = new ApprovalStore(tempDir.resolve("approvals.json").toString(), false);
        ApprovalService approvalService = new ApprovalService(approvalStore);
        TenantPolicyStore tenantPolicyStore =
                new TenantPolicyStore(tempDir.resolve("tenant-policies.json").toString(), false);
        TenantPolicyService tenantPolicyService = new TenantPolicyService(tenantPolicyStore);
        PolicyDecisionEngine policyDecisionEngine = new PolicyDecisionEngine(tenantPolicyService);

        service = new WorkbenchServiceImpl(nodeManagerClient, governanceService,
                policyDecisionEngine, approvalService, runtimeService, repositoryService,
                engine.getHistoryService(), engine.getTaskService(), engine.getExternalTaskService(),
                new TwinModelGenerator(), stateStore,
                new ProcessModelFileStore(tempDir.resolve("models").toString()), archiveStore,
                delegateClassGenerator, generator, new SpringBootProjectLauncher(), tracker);

        bindingFile = tempDir.resolve("bindings.json");
        wireCapabilityBindingRegistry(service,
                new CapabilityBindingRegistry(new CapabilityBindingStore(bindingFile.toString(), true)));

        realDelegate.set(execution -> { }); // no real automation needed for this test

        String xml = Bpmn.convertToString(genericProcess());
        ProcessModel model = service.saveProcessModel(null, "Generic binding compensation fixture", xml);
        TwinProcess twin = service.launchProcess(model.getId());
        service.connectActivity(twin.getId(), ACTIVITY, ACTIVITY);
        twinId = twin.getId();
    }

    @AfterEach
    void tearDown() {
        if (engine != null) {
            engine.close();
        }
    }

    private static void wireCapabilityBindingRegistry(WorkbenchServiceImpl target, CapabilityBindingRegistry registry)
            throws Exception {
        Field field = WorkbenchServiceImpl.class.getDeclaredField("capabilityBindingRegistry");
        field.setAccessible(true);
        field.set(target, registry);
    }

    private void stubAvailable(String agentType, String agentName) {
        AgentAvailabilityResult available = new AgentAvailabilityResult(agentType, true, agentName,
                "Available in catalog", Map.of(), "synthetic provider", List.of(), List.of(), List.of());
        when(nodeManagerClient.checkAgentAvailability(agentType)).thenReturn(available);
    }

    private Object twinVariable(String suffix) {
        String twinProcessId = twinInstanceId();
        return runtimeService.getVariable(twinProcessId, "evolvedAgent_" + ACTIVITY + suffix);
    }

    private String twinInstanceId() {
        TwinProcess twin = service.getTwinProcess(twinId);
        return twin.getTwinProcessId();
    }

    // ---- success case: the durable store and the runtime variable agree ----

    @Test
    void aSuccessfulBindIsReflectedInBothRuntimeVariablesAndTheDurableStore() {
        stubAvailable("synthetic-type", "provider-x");

        AgentDecision decision = service.evolveActivity(twinId, ACTIVITY, "synthetic-type");

        assertThat(decision.isApproved()).as(decision.getReason()).isTrue();
        assertThat(runtimeService.getVariable(twinInstanceId(), "evolvedAgent_" + ACTIVITY))
                .isEqualTo("provider-x");
        List<CapabilityBinding> persisted = new CapabilityBindingStore(bindingFile.toString(), true).load();
        assertThat(persisted).hasSize(1);
        assertThat(persisted.get(0).providerId()).isEqualTo("provider-x");
    }

    // ---- first-bind persistence failure: no runtime binding, no durable binding ----

    @Test
    void aFirstBindThatFailsToPersistLeavesNoRuntimeOrDurableBinding() throws IOException {
        stubAvailable("synthetic-type", "provider-x");
        // Force the NEXT save() to fail: replace the backing file with a directory.
        Files.createDirectory(bindingFile);

        AgentDecision decision = service.evolveActivity(twinId, ACTIVITY, "synthetic-type");

        assertThat(decision.isApproved())
                .as("persistence failed - the operation must not be reported as successful").isFalse();
        assertThat(runtimeService.getVariable(twinInstanceId(), "evolvedAgent_" + ACTIVITY)).isNull();
        assertThat(runtimeService.getVariable(twinInstanceId(), "evolvedAgentType_" + ACTIVITY)).isNull();
    }

    // ---- rebind persistence failure: the PREVIOUS binding must survive, not be cleared ----

    @Test
    void aRebindThatFailsToPersistRestoresThePreviousProviderRatherThanClearingIt() throws IOException {
        stubAvailable("synthetic-type", "provider-x");
        AgentDecision firstBind = service.evolveActivity(twinId, ACTIVITY, "synthetic-type");
        assertThat(firstBind.isApproved()).as(firstBind.getReason()).isTrue();

        // Now force the SECOND save() (the rebind) to fail.
        Files.delete(bindingFile);
        Files.createDirectory(bindingFile);
        stubAvailable("synthetic-type-2", "provider-y");

        AgentDecision rebind = service.evolveActivity(twinId, ACTIVITY, "synthetic-type-2");

        assertThat(rebind.isApproved())
                .as("persistence failed - the rebind must not be reported as successful").isFalse();
        // The runtime variable is restored to EXACTLY the previous provider - never cleared.
        assertThat(runtimeService.getVariable(twinInstanceId(), "evolvedAgent_" + ACTIVITY))
                .as("compensation must restore the previous binding, not merely remove it")
                .isEqualTo("provider-x");
        assertThat(runtimeService.getVariable(twinInstanceId(), "evolvedAgentType_" + ACTIVITY))
                .isEqualTo("synthetic-type");
    }
}
