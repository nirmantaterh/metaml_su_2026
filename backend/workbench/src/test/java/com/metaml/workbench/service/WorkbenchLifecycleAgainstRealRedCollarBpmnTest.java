package com.metaml.workbench.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.camunda.bpm.engine.ExternalTaskService;
import org.camunda.bpm.engine.HistoryService;
import org.camunda.bpm.engine.ProcessEngine;
import org.camunda.bpm.engine.ProcessEngineConfiguration;
import org.camunda.bpm.engine.RepositoryService;
import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.engine.TaskService;
import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.camunda.bpm.engine.delegate.JavaDelegate;
import org.camunda.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.metaml.workbench.automation.CreditRiskAssessorExecutor;
import com.metaml.workbench.automation.DataEnricherExecutor;
import com.metaml.workbench.automation.DefaultProjectAutomationService;
import com.metaml.workbench.automation.NotifierExecutor;
import com.metaml.workbench.automation.ProjectAutomationService;
import com.metaml.workbench.automation.RecommenderExecutor;
import com.metaml.workbench.automation.ValidatorExecutor;
import com.metaml.workbench.bpmn.TwinModelGenerator;
import com.metaml.workbench.client.AgentAvailabilityResult;
import com.metaml.workbench.client.NodeManagerClient;
import com.metaml.workbench.codegen.DelegateClassGenerator;
import com.metaml.workbench.delegate.TwinAutomationDelegate;
import com.metaml.workbench.generation.SpringBootProjectGenerator;
import com.metaml.workbench.generation.SpringBootProjectLauncher;
import com.metaml.workbench.governance.ApprovalService;
import com.metaml.workbench.governance.PolicyDecisionEngine;
import com.metaml.workbench.model.AgentDecision;
import com.metaml.workbench.model.ProcessModel;
import com.metaml.workbench.model.TwinActivityExecutionState;
import com.metaml.workbench.model.TwinProcess;
import com.metaml.workbench.store.ProcessModelArchiveStore;
import com.metaml.workbench.store.ProcessModelFileStore;
import com.metaml.workbench.store.WorkbenchStateStore;
import com.metaml.workbench.workflow.WorkflowEventStore;
import com.metaml.workbench.workflow.WorkflowStateTracker;

/**
 * Verifies workbench lifecycle and evolution against RedCollar BPMN models.
 */
@Tag("slow")
class WorkbenchLifecycleAgainstRealRedCollarBpmnTest {

    @TempDir
    Path tempDir;

    // First activity in RedCollar.Manuf upstream of order approval gateway.
    private static final String FIRST_ACTIVITY_ID = "_E12DB58F-C11B-42BF-BA46-88B171B228EC";

    private static Path redCollarDir() {
        String configured = System.getProperty("redcollar.bpmn.dir", System.getenv("REDCOLLAR_BPMN_DIR"));
        return (configured != null && !configured.isBlank()) ? Path.of(configured) : Path.of("../..");
    }

    private ProcessEngine engine;
    private RuntimeService runtimeService;
    private NodeManagerClient nodeManagerClient;
    private GovernanceService governanceService;
    private WorkbenchServiceImpl service;

    @BeforeEach
    void setUp() throws Exception {
        Assumptions.assumeTrue(Files.isRegularFile(redCollarDir().resolve("Manuf-camunda.bpmn")),
                "RedCollar Manuf-camunda.bpmn not found at " + redCollarDir().toAbsolutePath()
                        + " - point redcollar.bpmn.dir or REDCOLLAR_BPMN_DIR at the supplied file");

        // Break circular dependency between process engine delegate bean and WorkbenchService via holder.
        AtomicReference<JavaDelegate> realDelegate = new AtomicReference<>();
        JavaDelegate delegateBridge = execution -> realDelegate.get().execute(execution);

        ProcessEngineConfiguration config = ProcessEngineConfiguration
                .createStandaloneInMemProcessEngineConfiguration();
        config.setJdbcUrl("jdbc:h2:mem:rc-pathb-" + java.util.UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        config.setJdbcDriver("org.h2.Driver");
        config.setJdbcUsername("sa");
        config.setJdbcPassword("");
        config.setDatabaseSchemaUpdate(ProcessEngineConfiguration.DB_SCHEMA_UPDATE_TRUE);
        config.setJobExecutorActivate(false);
        // Configure history level and TTL to support variable update queries.
        config.setHistory(ProcessEngineConfiguration.HISTORY_FULL);
        if (config instanceof ProcessEngineConfigurationImpl configImpl) {
            configImpl.setHistoryTimeToLive("180");
            configImpl.setBeans(Map.of("twinAutomationDelegate", delegateBridge));
        }
        engine = config.buildProcessEngine();
        runtimeService = engine.getRuntimeService();
        RepositoryService repositoryService = engine.getRepositoryService();
        HistoryService historyService = engine.getHistoryService();
        TaskService taskService = engine.getTaskService();
        ExternalTaskService externalTaskService = engine.getExternalTaskService();

        nodeManagerClient = mock(NodeManagerClient.class);
        governanceService = mock(GovernanceService.class);
        when(governanceService.reserveEvolutionSlot(anyString(), anyString()))
                .thenReturn(new com.metaml.workbench.model.GovernanceDecision(true, null));
        when(governanceService.reserveTwinExecutionSlot(anyString()))
                .thenReturn(new com.metaml.workbench.model.GovernanceDecision(true, null));

        WorkflowEventStore eventStore = new WorkflowEventStore(tempDir.resolve("events.json").toString(), true);
        WorkflowStateTracker tracker = new WorkflowStateTracker(eventStore);
        invokeDeclared(tracker, WorkflowStateTracker.class, "restore");

        WorkbenchStateStore stateStore = mock(WorkbenchStateStore.class);
        ApprovalService approvalService = mock(ApprovalService.class);
        when(stateStore.load()).thenReturn(new WorkbenchStateStore.Snapshot(List.of()));
        when(approvalService.listAllApproved()).thenReturn(List.of());
        ProcessModelArchiveStore archiveStore = mock(ProcessModelArchiveStore.class);
        when(archiveStore.findAll()).thenReturn(List.of());
        DelegateClassGenerator delegateClassGenerator = mock(DelegateClassGenerator.class);
        when(delegateClassGenerator.generate(anyString(), anyString())).thenReturn(List.of());

        Path templateDir = tempDir.resolve("template");
        Files.createDirectories(templateDir);
        Files.writeString(templateDir.resolve("pom.xml"), "<project>fake</project>");
        SpringBootProjectGenerator generator = new SpringBootProjectGenerator(templateDir.toString(),
                tempDir.resolve("generated-projects").toString(), new TwinModelGenerator(),
                new DelegateClassGenerator(), new com.metaml.workbench.codegen.ExternalTaskWorkerGenerator());

        service = new WorkbenchServiceImpl(nodeManagerClient, governanceService,
                mock(PolicyDecisionEngine.class), approvalService, runtimeService, repositoryService,
                historyService, taskService, externalTaskService,
                new TwinModelGenerator(), stateStore,
                new ProcessModelFileStore(tempDir.resolve("models").toString()), archiveStore,
                delegateClassGenerator, generator, new SpringBootProjectLauncher(), tracker);

        ProjectAutomationService automation = new DefaultProjectAutomationService(List.of(
                new CreditRiskAssessorExecutor(), new ValidatorExecutor(), new DataEnricherExecutor(),
                new NotifierExecutor(), new RecommenderExecutor()));
        realDelegate.set(new TwinAutomationDelegate(Map.of("default", automation), service, repositoryService));
    }

    @AfterEach
    void tearDown() {
        if (engine != null) {
            engine.close();
        }
    }

    // Mirrors the Node Manager catalog contract: an agent TYPE resolves to that type's agent NAME.
    private void stubCatalog(String agentType, String agentName) {
        AgentAvailabilityResult available = new AgentAvailabilityResult();
        available.setAvailable(true);
        available.setAgentName(agentName);
        available.setReason("Available in catalog");
        when(nodeManagerClient.checkAgentAvailability(agentType)).thenReturn(available);
    }

    @Test
    void workbenchLifecycleEvolvesAndExecutesARealComponentOnTheRealRedCollarBpmn() throws Exception {
        String manufXml = Files.readString(redCollarDir().resolve("Manuf-camunda.bpmn"));

        ProcessModel model = service.saveProcessModel(null, "RedCollar Manuf (Path B)", manufXml);
        assertThat(model.getProcessDefinitionId()).isNotBlank();

        TwinProcess twin = service.launchProcess(model.getId());
        assertThat(twin.getOriginalProcessId()).isNotBlank();
        assertThat(twin.getTwinProcessId()).isNotBlank();

        service.connectActivity(twin.getId(), FIRST_ACTIVITY_ID, FIRST_ACTIVITY_ID);

        TwinActivityExecutionState discovered = service.getActivityExecutionState(twin.getId(), FIRST_ACTIVITY_ID);
        assertThat(discovered.getActiveInstances())
                .as("first RedCollar activity must be an active runtime instance right after launch")
                .hasSize(1);
        assertThat(discovered.getStatus()).isEqualTo("NOT_STARTED");

        stubCatalog("validator", "validator-agent-01");
        AgentDecision decision = service.evolveActivity(twin.getId(), FIRST_ACTIVITY_ID, "validator");
        assertThat(decision.isApproved())
                .as("evolution must be approved on a live real-BPMN activity: %s", decision.getReason())
                .isTrue();
        assertThat(decision.getAgentName()).isEqualTo("validator-agent-01");

        TwinActivityExecutionState bound = service.getActivityExecutionState(twin.getId(), FIRST_ACTIVITY_ID);
        assertThat(bound.getAgentName()).isEqualTo("validator-agent-01");
        assertThat(bound.getStatus()).isEqualTo("BOUND");

        service.bridgeActivityEvent(twin.getId(), FIRST_ACTIVITY_ID);

        TwinActivityExecutionState executed = service.getActivityExecutionState(twin.getId(), FIRST_ACTIVITY_ID);
        assertThat(executed.getAgentName()).isEqualTo("validator-agent-01");
        assertThat(executed.getStatus())
                .as("bound component must actually have executed on the twin; summary=%s", executed.getSummary())
                .isEqualTo("EXECUTED");
        assertThat(executed.getSummary())
                .as("the executor that ran must correspond to the AI-selected/catalog-validated component")
                .contains("ValidatorExecutor");
        assertThat(executed.getOutput())
                .as("fresh output must come from the component that was actually bound")
                .containsKey("validationPassed");
        assertThat(executed.getSummary()).doesNotContain("CreditRiskAssessorExecutor");
    }

    @Test
    void adifferentCatalogComponentDispatchesToItsOwnExecutorOnTheSameRealActivity() throws Exception {
        String manufXml = Files.readString(redCollarDir().resolve("Manuf-camunda.bpmn"));
        ProcessModel model = service.saveProcessModel(null, "RedCollar Manuf (Path B, alt agent)", manufXml);
        TwinProcess twin = service.launchProcess(model.getId());
        service.connectActivity(twin.getId(), FIRST_ACTIVITY_ID, FIRST_ACTIVITY_ID);

        stubCatalog("credit-risk-assessor", "credit-risk-agent-01");
        AgentDecision decision = service.evolveActivity(twin.getId(), FIRST_ACTIVITY_ID, "credit-risk-assessor");
        assertThat(decision.isApproved()).as(decision.getReason()).isTrue();
        assertThat(decision.getAgentName()).isEqualTo("credit-risk-agent-01");

        service.bridgeActivityEvent(twin.getId(), FIRST_ACTIVITY_ID);

        TwinActivityExecutionState executed = service.getActivityExecutionState(twin.getId(), FIRST_ACTIVITY_ID);
        assertThat(executed.getStatus()).isEqualTo("EXECUTED");
        assertThat(executed.getSummary()).contains("CreditRiskAssessorExecutor");
        assertThat(executed.getOutput()).containsKey("riskScore");
        assertThat(executed.getSummary()).doesNotContain("ValidatorExecutor");
    }

    private static void invokeDeclared(Object target, Class<?> type, String methodName) {
        try {
            Method method = type.getDeclaredMethod(methodName);
            method.setAccessible(true);
            method.invoke(target);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
