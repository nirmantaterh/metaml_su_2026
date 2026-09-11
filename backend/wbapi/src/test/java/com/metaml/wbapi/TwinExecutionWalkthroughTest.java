package com.metaml.wbapi;

import org.camunda.bpm.engine.HistoryService;
import org.camunda.bpm.engine.ManagementService;
import org.camunda.bpm.engine.RepositoryService;
import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.engine.TaskService;
import org.camunda.bpm.engine.history.HistoricVariableInstance;
import org.camunda.bpm.engine.runtime.ActivityInstance;
import org.camunda.bpm.engine.runtime.TransitionInstance;
import org.camunda.bpm.engine.task.Task;
import org.camunda.bpm.model.bpmn.Bpmn;
import org.camunda.bpm.model.bpmn.BpmnModelInstance;
import org.camunda.bpm.model.bpmn.instance.ExclusiveGateway;
import org.camunda.bpm.model.bpmn.instance.SequenceFlow;
import org.camunda.bpm.model.bpmn.instance.ReceiveTask;
import org.camunda.bpm.model.bpmn.instance.ServiceTask;
import org.camunda.bpm.model.xml.instance.ModelElementInstance;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.metaml.workbench.bpmn.AgentOutputDeclarations;
import com.metaml.workbench.client.AgentAvailabilityResult;
import com.metaml.workbench.client.NodeManagerClient;
import com.metaml.workbench.model.AgentDecision;
import com.metaml.workbench.model.ProcessModel;
import com.metaml.workbench.model.TwinAdvance;
import com.metaml.workbench.model.TwinProcess;
import com.metaml.workbench.service.GovernanceService;
import com.metaml.workbench.service.WorkbenchService;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

// Verifies twin token synchronization across message-correlated receive tasks.
@IsolatedWorkbenchTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:metaml-test;DB_CLOSE_DELAY=-1"
})
class TwinExecutionWalkthroughTest {

    private static final String KYC = "Task_KYC";
    private static final String AML = "Task_AML";
    private static final String OFAC = "Task_OFAC";
    private static final String CREDIT = "Task_Credit";
    private static final String APPROVE = "Task_Approve";
    private static final String EXECUTE = "Task_Execute";
    private static final String NOTIFY = "Task_Notify";
    private static final String ESCALATE = "Task_Escalate";

    private static final String BRIDGE_AGENT = "validator-agent-01";
    private static final String TWIN_DELEGATE = "${twinAutomationDelegate}";

    @MockitoBean
    private NodeManagerClient nodeManagerClient;

    @Autowired
    private WorkbenchService workbenchService;
    @Autowired
    private GovernanceService governanceService;
    @Autowired
    private RuntimeService runtimeService;
    @Autowired
    private RepositoryService repositoryService;
    @Autowired
    private TaskService taskService;
    @Autowired
    private HistoryService historyService;
    @Autowired
    private ManagementService managementService;
    @Autowired
    private AgentOutputDeclarations outputDeclarations;

    @BeforeEach
    void stubTheCatalogAndOpenBothQuotas() {
        given(nodeManagerClient.checkAgentAvailability(anyString())).willAnswer(call -> {
            String type = call.getArgument(0);
            return new AgentAvailabilityResult(type, true, type + "-agent-01", "stub catalog", false);
        });
        governanceService.updatePolicy(Set.of(), 20, 200);
    }

    @AfterEach
    void putTheQuotasBack() {
        governanceService.updatePolicy(Set.of(), 20, 200);
    }

    @Test
    void theTwinsOwnTokenWalksTheWireTransferAsTheOriginalIsCompleted() throws IOException {
        ProcessModel model = workbenchService.saveProcessModel(null, "citi wire transfer executable twin",
                citibankBpmn());
        TwinProcess twin = workbenchService.launchProcess(model.getId());

        assertThat(twin.getTwinProcessDefinitionId())
                .isNotBlank()
                .isNotEqualTo(twin.getProcessDefinitionId());
        assertThat(openTasks(twin)).containsExactly(KYC);
        assertThat(twinToken(twin)).containsExactly(KYC);
        assertThat(taskService.createTaskQuery().processInstanceId(twin.getTwinProcessId()).count()).isZero();

        connect(twin, KYC, AML, OFAC, CREDIT, APPROVE, EXECUTE, NOTIFY);

        // Initial activity start event fires before twin process registration completes; manually bridged.
        AgentDecision kyc = workbenchService.bridgeActivityEvent(twin.getId(), KYC);
        assertThat(kyc.isApproved()).isTrue();
        assertThat(twinVariable(twin, "evolvedAgent_" + KYC)).isEqualTo(BRIDGE_AGENT);
        assertThat(twinVariable(twin, "twinAutomation_" + KYC)).isNotNull();
        assertThat(twinToken(twin)).containsExactly(AML, CREDIT, OFAC);
        assertThat(openTasks(twin)).containsExactly(KYC);

        assertThat(workbenchService.completeCurrentTasks(twin.getId())).hasSize(1);

        assertThat(openTasks(twin)).containsExactlyInAnyOrder(AML, OFAC, CREDIT);
        assertThat(twinVariable(twin, "twinAutomation_" + AML)).isNotNull();
        assertThat(twinVariable(twin, "twinAutomation_" + OFAC)).isNotNull();
        assertThat(twinVariable(twin, "twinAutomation_" + CREDIT)).isNotNull();
        assertThat(twinToken(twin)).containsExactly(APPROVE);

        assertThat(workbenchService.completeCurrentTasks(twin.getId())).hasSize(3);
        assertThat(openTasks(twin)).containsExactly(APPROVE);
        assertThat(twinToken(twin)).containsExactly(EXECUTE);

        assertThat(workbenchService.completeCurrentTasks(twin.getId())).hasSize(1);
        assertThat(openTasks(twin)).containsExactly(EXECUTE);
        assertThat(twinToken(twin)).containsExactly(NOTIFY);

        // Twin advances upon entry to the corresponding activity, completing before the original process.
        assertThat(workbenchService.completeCurrentTasks(twin.getId())).hasSize(1);
        assertThat(openTasks(twin)).containsExactly(NOTIFY);
        assertThat(twinToken(twin)).isEmpty();
        assertThat(workbenchService.getTwinProcess(twin.getId()).getStatus())
                .isEqualTo("ORIGINAL_RUNNING_TWIN_ENDED");

        assertThat(twinReached(twin, KYC)).isTrue();
        assertThat(twinReached(twin, "Gateway_ParallelSplit")).isTrue();
        assertThat(twinReached(twin, "Gateway_ParallelJoin")).isTrue();
        assertThat(twinReached(twin, NOTIFY)).isTrue();
        assertThat(twinReached(twin, "EndEvent_Success")).isTrue();

        assertThat(workbenchService.completeCurrentTasks(twin.getId())).hasSize(1);
        assertThat(workbenchService.getTwinProcess(twin.getId()).getStatus()).isEqualTo("ENDED");

        // Twin execution count and evolution count are tracked on separate budgets.
        assertThat(governanceService.getUsage(twin.getId()).getTwinExecutionCount()).isEqualTo(7);
        assertThat(governanceService.getUsage(twin.getId()).getEvolutionCount()).isEqualTo(7);
    }

    @Test
    void theDefaultProjectAutomationRunsAndSetsVariablesOnTheTwin() throws IOException {
        ProcessModel model = workbenchService.saveProcessModel(null, "citi wire transfer default automation",
                citibankBpmn());
        TwinProcess twin = workbenchService.launchProcess(model.getId());

        assertThat(twin.getProjectId()).isEqualTo("default");
        workbenchService.connectActivity(twin.getId(), KYC, KYC);
        assertThat(workbenchService.bridgeActivityEvent(twin.getId(), KYC).isApproved()).isTrue();

        assertThat(twinVariable(twin, "twinAutomation_" + KYC).toString())
                .contains("ValidatorExecutor executed for Task_KYC");
        assertThat(twinVariable(twin, "twinAutomationOutput_validationPassed_" + KYC)).isEqualTo(true);

        assertThat(twinVariable(twin, "twinAutomation_" + AML)).isNull();
    }

    @Test
    void evolvedCreditRiskAssessorInvokesCreditRiskAssessorExecutorAndSetsOutputs() throws IOException {
        ProcessModel model = workbenchService.saveProcessModel(null, "citi wire transfer credit risk execution",
                citibankBpmn());
        TwinProcess twin = workbenchService.launchProcess(model.getId());

        workbenchService.connectActivity(twin.getId(), KYC, KYC);
        AgentDecision evolved = workbenchService.evolveActivity(twin.getId(), KYC, "credit-risk-assessor");
        assertThat(evolved.isApproved()).isTrue();
        assertThat(evolved.getAgentName()).isEqualTo("credit-risk-assessor-agent-01");

        TwinAdvance advance = workbenchService.advanceTwinActivity(twin.getId(), KYC);
        assertThat(advance.isAdvanced()).isTrue();

        assertThat(twinVariable(twin, "twinAutomation_" + KYC).toString())
                .contains("CreditRiskAssessorExecutor executed for Task_KYC");
        assertThat(twinVariable(twin, "twinAutomationOutput_riskFlagged_" + KYC)).isEqualTo(true);
        assertThat(twinVariable(twin, "twinAutomationOutput_riskScore_" + KYC)).isEqualTo(85);
        assertThat(twinVariable(twin, "agentFlaggedRisk")).isEqualTo(true);
    }

    // Governance policy rejection halts advancement without raising an exception or mutating state.
    @Test
    void aDeniedTwinExecutionLeavesTheTokenWhereItIsWithoutThrowing() throws IOException {
        ProcessModel model = workbenchService.saveProcessModel(null, "citi wire transfer twin quota",
                citibankBpmn());
        TwinProcess twin = workbenchService.launchProcess(model.getId());
        workbenchService.connectActivity(twin.getId(), KYC, KYC);

        governanceService.updatePolicy(null, null, 0);

        AgentDecision kyc = workbenchService.bridgeActivityEvent(twin.getId(), KYC);
        assertThat(kyc.isApproved()).isTrue();
        assertThat(twinVariable(twin, "evolvedAgent_" + KYC)).isEqualTo(BRIDGE_AGENT);

        assertThat(twinToken(twin)).containsExactly(KYC);
        assertThat(twinVariable(twin, "twinAutomation_" + KYC)).isNull();
        assertThat(twin.getEventLog()).anyMatch(entry -> entry.contains("left parked by governance"));

        TwinAdvance blocked = workbenchService.advanceTwinActivity(twin.getId(), KYC);
        assertThat(blocked.isAdvanced()).isFalse();
        assertThat(blocked.getReason()).contains("Twin execution quota exceeded");
        assertThat(twinToken(twin)).containsExactly(KYC);
        assertThat(managementService.createJobQuery()
                .processInstanceId(twin.getTwinProcessId()).count()).isZero();

        assertThatCode(() -> workbenchService.completeCurrentTasks(twin.getId())).doesNotThrowAnyException();
        assertThat(openTasks(twin)).containsExactlyInAnyOrder(AML, OFAC, CREDIT);

        governanceService.updatePolicy(null, null, 200);
        assertThat(workbenchService.advanceTwinActivity(twin.getId(), KYC).isAdvanced()).isTrue();
        assertThat(twinVariable(twin, "twinAutomation_" + KYC)).isNotNull();
    }

    @Test
    void theGeneratedDefinitionKeepsTheShapeOfTheOriginalWithoutTheHumansOrTheTimer() throws IOException {
        ProcessModel model = workbenchService.saveProcessModel(null, "citi wire transfer generated model",
                citibankBpmn());
        TwinProcess twin = workbenchService.launchProcess(model.getId());
        BpmnModelInstance generated = repositoryService.getBpmnModelInstance(twin.getTwinProcessDefinitionId());

        // Receive task synchronizes with original activity; subsequent service task executes automation.
        ReceiveTask credit = generated.getModelElementById(CREDIT);
        assertThat(credit).isNotNull();
        assertThat(credit.getName()).isEqualTo("Assess Credit Risk");
        assertThat(credit.getMessage().getName()).isEqualTo("TwinAdvance_" + CREDIT);
        // Execution listeners are moved from the receive task to the paired automation service task.
        assertThat(credit.getExtensionElements().getElementsQuery()
                .filterByType(org.camunda.bpm.model.bpmn.instance.camunda.CamundaExecutionListener.class)
                .list()).isEmpty();

        ServiceTask creditAutomation = generated.getModelElementById(
                com.metaml.workbench.bpmn.TwinModelGenerator.automationTaskId(CREDIT));
        assertThat(creditAutomation).isNotNull();
        assertThat(creditAutomation.getCamundaDelegateExpression()).isEqualTo(TWIN_DELEGATE);
        assertThat(creditAutomation.isCamundaAsyncBefore()).isFalse();
        assertThat(creditAutomation.isCamundaAsyncAfter()).isFalse();

        assertThat(generated.getModelElementsByType(
                org.camunda.bpm.model.bpmn.instance.UserTask.class)).isEmpty();
        // Generated automation service tasks execute synchronously without asynchronous continuations.
        assertThat(Bpmn.convertToString(generated)).doesNotContain("asyncBefore");

        ExclusiveGateway checks = generated.getModelElementById("Gateway_ChecksPassed");
        assertThat(checks.getDefault()).isNotNull();
        assertThat(checks.getDefault().getId()).isEqualTo("Flow_Checks_Pass");
        SequenceFlow fail = generated.getModelElementById("Flow_Checks_Fail");
        assertThat(fail.getName()).isEqualTo("No");
        assertThat(fail.getConditionExpression().getTextContent())
                .isEqualTo("${execution.getVariable('agentFlaggedRisk') == true}");
        assertThat(element(generated, "Gateway_ParallelSplit")).isNotNull();
        assertThat(element(generated, "EndEvent_RejectedIdentity")).isNotNull();

        // Boundary timers and orphaned escalation paths are omitted from generated twin models.
        assertThat(element(generated, "BoundaryEvent_Timeout")).isNull();
        assertThat(element(generated, "Task_EscalateTimeout")).isNull();
        assertThat(element(generated, "EndEvent_EscalatedTimeout")).isNull();

        assertThat(outputDeclarations.forActivity(twin.getTwinProcessDefinitionId(), CREDIT))
                .containsEntry("riskFlagged", "agentFlaggedRisk");
        String xml = Bpmn.convertToString(generated);
        assertThat(xml).contains("identityDocumentType").contains("transferAmount");

        assertThat(repositoryService.getProcessDefinition(twin.getTwinProcessDefinitionId()).getKey())
                .isEqualTo("Process_WireTransfer_twin");
    }

    // Downstream activities of a multi-instance task must stay at the process root level.
    @Test
    void downstreamOfAMultiInstanceActivityStaysAtTheTopLevelNotNestedInsideItsWrapper() throws IOException {
        BpmnModelInstance original = Bpmn.readModelFromStream(
                new java.io.ByteArrayInputStream(gradAdmissionBpmn().getBytes(StandardCharsets.UTF_8)));
        BpmnModelInstance twin = new com.metaml.workbench.bpmn.TwinModelGenerator().generate(original);

        ModelElementInstance gateway = twin.getModelElementById("Gateway_MajorityApproved");
        ModelElementInstance endAdmitted = twin.getModelElementById("EndEvent_Admitted");
        assertThat(gateway.getParentElement().getElementType().getTypeName()).isEqualTo("process");
        assertThat(endAdmitted.getParentElement().getElementType().getTypeName()).isEqualTo("process");

        ReceiveTask committeeReview = (ReceiveTask) twin.getModelElementById("Task_CommitteeReview");
        assertThat(committeeReview.getOutgoing()).hasSize(1);
    }

    // Twin evaluates gateway conditions against its own execution variables independently of the original.
    @Test
    void theTwinTakesItsOwnDefaultBranchWhenTheOriginalEscalates() throws IOException {
        given(nodeManagerClient.checkAgentAvailability(anyString())).willAnswer(call -> {
            String type = call.getArgument(0);
            return new AgentAvailabilityResult(type, true, type + "-agent-01", "stub catalog",
                    "credit-risk-assessor".equals(type));
        });

        ProcessModel model = workbenchService.saveProcessModel(null, "citi wire transfer twin escalation",
                citibankBpmn());
        TwinProcess twin = workbenchService.launchProcess(model.getId());
        connect(twin, KYC, AML, OFAC, CREDIT, ESCALATE);

        assertThat(workbenchService.bridgeActivityEvent(twin.getId(), KYC).isApproved()).isTrue();
        assertThat(workbenchService.completeCurrentTasks(twin.getId())).hasSize(1);
        assertThat(workbenchService.evolveActivity(twin.getId(), CREDIT, "credit-risk-assessor")
                .isRiskFlagged()).isTrue();
        assertThat(workbenchService.completeCurrentTasks(twin.getId())).hasSize(3);

        assertThat(openTasks(twin)).containsExactly(ESCALATE);
        assertThat(twinToken(twin)).containsExactly(APPROVE);
        assertThat(workbenchService.advanceTwinActivity(twin.getId(), ESCALATE).isAdvanced()).isFalse();
    }

    // Twin deployments use duplicate filtering keyed by process model ID to reuse existing definitions.
    @Test
    void relaunchingTheSameModelReusesTheTwinDeployment() throws IOException {
        ProcessModel model = workbenchService.saveProcessModel(null, "citi wire transfer relaunched",
                citibankBpmn());

        TwinProcess first = workbenchService.launchProcess(model.getId());
        long deploymentsAfterFirst = repositoryService.createDeploymentQuery().count();

        TwinProcess second = workbenchService.launchProcess(model.getId());

        assertThat(repositoryService.createDeploymentQuery().count()).isEqualTo(deploymentsAfterFirst);
        assertThat(second.getTwinProcessDefinitionId()).isEqualTo(first.getTwinProcessDefinitionId());
        assertThat(second.getTwinProcessId()).isNotEqualTo(first.getTwinProcessId());
        assertThat(twinToken(first)).containsExactly(KYC);
        assertThat(twinToken(second)).containsExactly(KYC);
    }

    @Test
    void connectRejectsATwinActivityTheGeneratorLeftOut() throws IOException {
        ProcessModel model = workbenchService.saveProcessModel(null, "citi wire transfer bad twin link",
                citibankBpmn());
        TwinProcess twin = workbenchService.launchProcess(model.getId());

        assertThatThrownBy(() -> workbenchService.connectActivity(twin.getId(), KYC, "Task_EscalateTimeout"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("twinActivityId");
        assertThatThrownBy(() -> workbenchService.connectActivity(twin.getId(), "Task_NotInTheDiagram", KYC))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("originalActivityId");
    }

    // Twin activities cannot be shared across multiple original activities to prevent state collision.
    @Test
    void connectRejectsATwinActivityAlreadyClaimedByADifferentOriginalActivity() throws IOException {
        ProcessModel model = workbenchService.saveProcessModel(null, "citi wire transfer many to one link",
                citibankBpmn());
        TwinProcess twin = workbenchService.launchProcess(model.getId());

        workbenchService.connectActivity(twin.getId(), KYC, KYC);

        assertThatThrownBy(() -> workbenchService.connectActivity(twin.getId(), AML, KYC))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(KYC)
                .hasMessageContaining(AML);
        assertThat(workbenchService.getTwinProcess(twin.getId()).findTwinActivityId(KYC)).contains(KYC);
        assertThat(workbenchService.getTwinProcess(twin.getId()).findTwinActivityId(AML)).isEmpty();

        workbenchService.connectActivity(twin.getId(), KYC, AML);
        assertThat(workbenchService.getTwinProcess(twin.getId()).findTwinActivityId(KYC)).contains(AML);
        workbenchService.connectActivity(twin.getId(), AML, KYC);
        assertThat(workbenchService.getTwinProcess(twin.getId()).findTwinActivityId(AML)).contains(KYC);
    }

    // Concurrent connection requests to the same twin activity synchronize on a per-twin lock
    // to prevent race conditions during activity mapping.
    @Test
    void concurrentConnectsToTheSameTwinActivityNeverBothSucceed() throws Exception {
        ProcessModel model = workbenchService.saveProcessModel(null, "citi wire transfer connect race",
                citibankBpmn());
        TwinProcess twin = workbenchService.launchProcess(model.getId());

        CyclicBarrier gate = new CyclicBarrier(2);
        Callable<Boolean> connectFromAml = () -> {
            gate.await(10, TimeUnit.SECONDS);
            try {
                workbenchService.connectActivity(twin.getId(), AML, KYC);
                return true;
            } catch (IllegalArgumentException e) {
                return false;
            }
        };
        Callable<Boolean> connectFromOfac = () -> {
            gate.await(10, TimeUnit.SECONDS);
            try {
                workbenchService.connectActivity(twin.getId(), OFAC, KYC);
                return true;
            } catch (IllegalArgumentException e) {
                return false;
            }
        };

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Boolean>> results = pool.invokeAll(List.of(connectFromAml, connectFromOfac));
            int succeeded = 0;
            for (Future<Boolean> result : results) {
                if (result.get(30, TimeUnit.SECONDS)) {
                    succeeded++;
                }
            }
            assertThat(succeeded).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }

        long claimants = List.of(AML, OFAC).stream()
                .filter(originalId -> workbenchService.getTwinProcess(twin.getId()).findTwinActivityId(originalId)
                        .map(KYC::equals).orElse(false))
                .count();
        assertThat(claimants).isEqualTo(1);
    }

    // Activity IDs ending in generator reserved suffixes (e.g. '_automate') are rejected to prevent collision.
    @Test
    void anActivityIdEndingInAReservedSuffixIsRejectedWithAClearReason() throws IOException {
        ProcessModel model = workbenchService.saveProcessModel(null, "reserved suffix test",
                reservedSuffixBpmn());
        assertThatThrownBy(() -> workbenchService.launchProcess(model.getId()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Task_A_automate");
    }

    // Sequential multi-instance expressions are preserved on the generated twin subprocess.
    @Test
    void aVariableExpressionCardinalityIsCarriedOverToTheTwin() throws IOException {
        ProcessModel model = workbenchService.saveProcessModel(null, "variable cardinality test",
                variableCardinalityLoopBpmn());
        TwinProcess twin = workbenchService.launchProcess(model.getId());
        BpmnModelInstance generated = repositoryService.getBpmnModelInstance(twin.getTwinProcessDefinitionId());

        assertThat(generated.getModelElementsByType(
                org.camunda.bpm.model.bpmn.instance.SubProcess.class)).isNotEmpty();
        assertThat(element(generated, "Task_Loop")).isNotNull();
        assertThat(element(generated,
                com.metaml.workbench.bpmn.TwinModelGenerator.automationTaskId("Task_Loop"))).isNotNull();
    }

    // Literal multi-instance completion conditions are preserved in the generated twin definition.
    @Test
    void aLiteralCompletionConditionIsCarriedOverToTheTwin() throws IOException {
        ProcessModel model = workbenchService.saveProcessModel(null, "completion condition test",
                completionConditionLoopBpmn());
        TwinProcess twin = workbenchService.launchProcess(model.getId());
        BpmnModelInstance generated = repositoryService.getBpmnModelInstance(twin.getTwinProcessDefinitionId());

        org.camunda.bpm.model.bpmn.instance.MultiInstanceLoopCharacteristics loop =
                generated.getModelElementsByType(
                        org.camunda.bpm.model.bpmn.instance.MultiInstanceLoopCharacteristics.class)
                        .iterator().next();
        assertThat(loop.getCompletionCondition()).isNotNull();
        assertThat(loop.getCompletionCondition().getTextContent()).isEqualTo("true");
    }

    @Test
    void anInclusiveGatewayIsSupportedAndLaunchesTwinSuccessfully() throws IOException {
        ProcessModel model = workbenchService.saveProcessModel(null, "inclusive gateway test",
                inclusiveGatewayBpmn());

        TwinProcess twin = workbenchService.launchProcess(model.getId());
        assertThat(twin).isNotNull();
        assertThat(twin.getTwinProcessId()).isNotNull();

        BpmnModelInstance twinModel = repositoryService.getBpmnModelInstance(
                repositoryService.createProcessDefinitionQuery()
                        .processDefinitionId(runtimeService.createProcessInstanceQuery()
                                .processInstanceId(twin.getTwinProcessId())
                                .singleResult().getProcessDefinitionId())
                        .singleResult().getId());

        assertThat(element(twinModel, "Gateway_Split")).isNotNull();
        assertThat(element(twinModel, "Gateway_Join")).isNotNull();
    }

    // Unmapped BPMN activity types fail generation fast with an explicit diagnostic message.
    @Test
    void generatingATwinFailsFastOnAnUnsupportedConstructInsteadOfSilentlyDroppingIt() throws IOException {
        ProcessModel model = workbenchService.saveProcessModel(null, "unsupported construct test",
                unsupportedServiceTaskBpmn());

        assertThatThrownBy(() -> workbenchService.launchProcess(model.getId()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Task_AutoStep")
                .hasMessageContaining("does not support");
    }

    private static ModelElementInstance element(BpmnModelInstance model, String id) {
        return model.getModelElementById(id);
    }

    private void connect(TwinProcess twin, String... activityIds) {
        for (String activityId : activityIds) {
            workbenchService.connectActivity(twin.getId(), activityId, activityId);
        }
    }

    private List<String> openTasks(TwinProcess twin) {
        return taskService.createTaskQuery()
                .processInstanceId(twin.getOriginalProcessId())
                .list()
                .stream()
                .map(Task::getTaskDefinitionKey)
                .sorted()
                .toList();
    }

    // Collects active activity and transition instances representing the current execution token.
    private List<String> twinToken(TwinProcess twin) {
        ActivityInstance tree = runtimeService.getActivityInstance(twin.getTwinProcessId());
        List<String> activityIds = new ArrayList<>();
        if (tree != null) {
            collectToken(tree, activityIds);
        }
        return activityIds.stream().sorted().toList();
    }

    private static void collectToken(ActivityInstance node, List<String> activityIds) {
        for (TransitionInstance waiting : node.getChildTransitionInstances()) {
            activityIds.add(waiting.getActivityId());
        }
        for (ActivityInstance child : node.getChildActivityInstances()) {
            activityIds.add(child.getActivityId());
            collectToken(child, activityIds);
        }
    }

    // Query completed twin variables from history since finished instances cannot be read from RuntimeService.
    private Object twinVariable(TwinProcess twin, String variableName) {
        HistoricVariableInstance variable = historyService.createHistoricVariableInstanceQuery()
                .processInstanceId(twin.getTwinProcessId())
                .variableName(variableName)
                .singleResult();
        return variable == null ? null : variable.getValue();
    }

    private boolean twinReached(TwinProcess twin, String activityId) {
        return historyService.createHistoricActivityInstanceQuery()
                .processInstanceId(twin.getTwinProcessId())
                .activityId(activityId)
                .count() > 0;
    }

    private static String citibankBpmn() throws IOException {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            Path candidate = dir.resolve("examples").resolve("citibank-wire-transfer.bpmn");
            if (Files.isRegularFile(candidate)) {
                return Files.readString(candidate, StandardCharsets.UTF_8);
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("no examples/citibank-wire-transfer.bpmn above "
                + Path.of("").toAbsolutePath());
    }

    private static String gradAdmissionBpmn() throws IOException {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            Path candidate = dir.resolve("examples").resolve("grad-admission-review.bpmn");
            if (Files.isRegularFile(candidate)) {
                return Files.readString(candidate, StandardCharsets.UTF_8);
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("no examples/grad-admission-review.bpmn above "
                + Path.of("").toAbsolutePath());
    }

    private static String reservedSuffixBpmn() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                                   id="Definitions_ReservedSuffix" targetNamespace="http://metaml.com/test">
                  <bpmn:process id="Process_ReservedSuffix" isExecutable="true">
                    <bpmn:startEvent id="StartEvent_1">
                      <bpmn:outgoing>Flow_1</bpmn:outgoing>
                    </bpmn:startEvent>
                    <bpmn:userTask id="Task_A_automate" name="Suspiciously named task">
                      <bpmn:incoming>Flow_1</bpmn:incoming>
                      <bpmn:outgoing>Flow_2</bpmn:outgoing>
                    </bpmn:userTask>
                    <bpmn:endEvent id="EndEvent_1">
                      <bpmn:incoming>Flow_2</bpmn:incoming>
                    </bpmn:endEvent>
                    <bpmn:sequenceFlow id="Flow_1" sourceRef="StartEvent_1" targetRef="Task_A_automate" />
                    <bpmn:sequenceFlow id="Flow_2" sourceRef="Task_A_automate" targetRef="EndEvent_1" />
                  </bpmn:process>
                </bpmn:definitions>
                """;
    }

    private static String inclusiveGatewayBpmn() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                                   id="Definitions_Inclusive" targetNamespace="http://metaml.com/test">
                  <bpmn:process id="Process_Inclusive" isExecutable="true">
                    <bpmn:startEvent id="StartEvent_1">
                      <bpmn:outgoing>Flow_1</bpmn:outgoing>
                    </bpmn:startEvent>
                    <bpmn:inclusiveGateway id="Gateway_Split" default="Flow_A">
                      <bpmn:incoming>Flow_1</bpmn:incoming>
                      <bpmn:outgoing>Flow_A</bpmn:outgoing>
                      <bpmn:outgoing>Flow_B</bpmn:outgoing>
                    </bpmn:inclusiveGateway>
                    <bpmn:userTask id="Task_A" name="Branch A">
                      <bpmn:incoming>Flow_A</bpmn:incoming>
                      <bpmn:outgoing>Flow_A_Join</bpmn:outgoing>
                    </bpmn:userTask>
                    <bpmn:userTask id="Task_B" name="Branch B">
                      <bpmn:incoming>Flow_B</bpmn:incoming>
                      <bpmn:outgoing>Flow_B_Join</bpmn:outgoing>
                    </bpmn:userTask>
                    <bpmn:inclusiveGateway id="Gateway_Join">
                      <bpmn:incoming>Flow_A_Join</bpmn:incoming>
                      <bpmn:incoming>Flow_B_Join</bpmn:incoming>
                      <bpmn:outgoing>Flow_End</bpmn:outgoing>
                    </bpmn:inclusiveGateway>
                    <bpmn:endEvent id="EndEvent_1">
                      <bpmn:incoming>Flow_End</bpmn:incoming>
                    </bpmn:endEvent>
                    <bpmn:sequenceFlow id="Flow_1" sourceRef="StartEvent_1" targetRef="Gateway_Split" />
                    <bpmn:sequenceFlow id="Flow_A" sourceRef="Gateway_Split" targetRef="Task_A" />
                    <bpmn:sequenceFlow id="Flow_B" sourceRef="Gateway_Split" targetRef="Task_B">
                      <bpmn:conditionExpression>${true}</bpmn:conditionExpression>
                    </bpmn:sequenceFlow>
                    <bpmn:sequenceFlow id="Flow_A_Join" sourceRef="Task_A" targetRef="Gateway_Join" />
                    <bpmn:sequenceFlow id="Flow_B_Join" sourceRef="Task_B" targetRef="Gateway_Join" />
                    <bpmn:sequenceFlow id="Flow_End" sourceRef="Gateway_Join" targetRef="EndEvent_1" />
                  </bpmn:process>
                </bpmn:definitions>
                """;
    }

    private static String unsupportedServiceTaskBpmn() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                                   xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
                                   id="Definitions_Unsupported" targetNamespace="http://metaml.com/test">
                  <bpmn:process id="Process_Unsupported" isExecutable="true">
                    <bpmn:startEvent id="StartEvent_1">
                      <bpmn:outgoing>Flow_1</bpmn:outgoing>
                    </bpmn:startEvent>
                    <bpmn:task id="Task_AutoStep" name="Auto step">
                      <bpmn:incoming>Flow_1</bpmn:incoming>
                      <bpmn:outgoing>Flow_2</bpmn:outgoing>
                    </bpmn:task>
                    <bpmn:endEvent id="EndEvent_1">
                      <bpmn:incoming>Flow_2</bpmn:incoming>
                    </bpmn:endEvent>
                    <bpmn:sequenceFlow id="Flow_1" sourceRef="StartEvent_1" targetRef="Task_AutoStep" />
                    <bpmn:sequenceFlow id="Flow_2" sourceRef="Task_AutoStep" targetRef="EndEvent_1" />
                  </bpmn:process>
                </bpmn:definitions>
                """;
    }

    private static String completionConditionLoopBpmn() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                                   id="Definitions_CompletionCondition" targetNamespace="http://metaml.com/test">
                  <bpmn:process id="Process_CompletionCondition" isExecutable="true">
                    <bpmn:startEvent id="StartEvent_1">
                      <bpmn:outgoing>Flow_1</bpmn:outgoing>
                    </bpmn:startEvent>
                    <bpmn:userTask id="Task_Loop" name="Loop Task">
                      <bpmn:incoming>Flow_1</bpmn:incoming>
                      <bpmn:outgoing>Flow_2</bpmn:outgoing>
                      <bpmn:multiInstanceLoopCharacteristics isSequential="true">
                        <bpmn:loopCardinality>3</bpmn:loopCardinality>
                        <bpmn:completionCondition>true</bpmn:completionCondition>
                      </bpmn:multiInstanceLoopCharacteristics>
                    </bpmn:userTask>
                    <bpmn:endEvent id="EndEvent_1">
                      <bpmn:incoming>Flow_2</bpmn:incoming>
                    </bpmn:endEvent>
                    <bpmn:sequenceFlow id="Flow_1" sourceRef="StartEvent_1" targetRef="Task_Loop" />
                    <bpmn:sequenceFlow id="Flow_2" sourceRef="Task_Loop" targetRef="EndEvent_1" />
                  </bpmn:process>
                </bpmn:definitions>
                """;
    }

    private static String variableCardinalityLoopBpmn() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                                   id="Definitions_VarCardinality" targetNamespace="http://metaml.com/test">
                  <bpmn:process id="Process_VarCardinality" isExecutable="true">
                    <bpmn:startEvent id="StartEvent_1">
                      <bpmn:outgoing>Flow_1</bpmn:outgoing>
                    </bpmn:startEvent>
                    <bpmn:userTask id="Task_Loop" name="Loop Task">
                      <bpmn:incoming>Flow_1</bpmn:incoming>
                      <bpmn:outgoing>Flow_2</bpmn:outgoing>
                      <bpmn:multiInstanceLoopCharacteristics isSequential="true">
                        <bpmn:loopCardinality>${1+1}</bpmn:loopCardinality>
                      </bpmn:multiInstanceLoopCharacteristics>
                    </bpmn:userTask>
                    <bpmn:endEvent id="EndEvent_1">
                      <bpmn:incoming>Flow_2</bpmn:incoming>
                    </bpmn:endEvent>
                    <bpmn:sequenceFlow id="Flow_1" sourceRef="StartEvent_1" targetRef="Task_Loop" />
                    <bpmn:sequenceFlow id="Flow_2" sourceRef="Task_Loop" targetRef="EndEvent_1" />
                  </bpmn:process>
                </bpmn:definitions>
                """;
    }
}
