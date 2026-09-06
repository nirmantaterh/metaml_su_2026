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

// This one is about the twin's own token: launched against a generated definition, every activity
// in it is a receive task waiting on a message of its own, and the bridge correlates one message at
// a time as the original commits its own steps.
//
// Same properties and the same NodeManagerClient stub as the other two walkthroughs, so all three
// share one Spring context.
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

    // the denial test below drops the twin-execution limit to nothing, and the two walkthroughs
    // sharing this context would inherit it
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
        // the original is where it always was, and the twin is waiting on its own copy of the
        // same activity rather than holding a user task nobody will ever open
        assertThat(openTasks(twin)).containsExactly(KYC);
        assertThat(twinToken(twin)).containsExactly(KYC);
        assertThat(taskService.createTaskQuery().processInstanceId(twin.getTwinProcessId()).count()).isZero();

        connect(twin, KYC, AML, OFAC, CREDIT, APPROVE, EXECUTE, NOTIFY);

        // KYC is the one activity the auto trigger can never catch: its start event fires inside
        // startProcessInstanceById, before launchProcess has the twin in its map
        AgentDecision kyc = workbenchService.bridgeActivityEvent(twin.getId(), KYC);
        assertThat(kyc.isApproved()).isTrue();
        assertThat(twinVariable(twin, "evolvedAgent_" + KYC)).isEqualTo(BRIDGE_AGENT);
        // the bridge moved it: KYC ran on the twin and the token is now sitting on all three
        // compliance checks, while the human still has KYC open
        assertThat(twinVariable(twin, "twinAutomation_" + KYC)).isNotNull();
        assertThat(twinToken(twin)).containsExactly(AML, CREDIT, OFAC);
        assertThat(openTasks(twin)).containsExactly(KYC);

        assertThat(workbenchService.completeCurrentTasks(twin.getId())).hasSize(1);

        // nobody called bridge or advance by hand for any of these - the after-commit trigger did
        assertThat(openTasks(twin)).containsExactlyInAnyOrder(AML, OFAC, CREDIT);
        assertThat(twinVariable(twin, "twinAutomation_" + AML)).isNotNull();
        assertThat(twinVariable(twin, "twinAutomation_" + OFAC)).isNotNull();
        assertThat(twinVariable(twin, "twinAutomation_" + CREDIT)).isNotNull();
        // all three twin branches ran and met at the join, so the token is one activity further on
        assertThat(twinToken(twin)).containsExactly(APPROVE);

        assertThat(workbenchService.completeCurrentTasks(twin.getId())).hasSize(3);
        assertThat(openTasks(twin)).containsExactly(APPROVE);
        assertThat(twinToken(twin)).containsExactly(EXECUTE);

        assertThat(workbenchService.completeCurrentTasks(twin.getId())).hasSize(1);
        assertThat(openTasks(twin)).containsExactly(EXECUTE);
        assertThat(twinToken(twin)).containsExactly(NOTIFY);

        // the twin's last activity runs while the original still has Notify open, so the twin ends
        // first. That's the shape of it: the twin does an activity the moment the original reaches
        // it, not when the original leaves it.
        assertThat(workbenchService.completeCurrentTasks(twin.getId())).hasSize(1);
        assertThat(openTasks(twin)).containsExactly(NOTIFY);
        assertThat(twinToken(twin)).isEmpty();
        assertThat(workbenchService.getTwinProcess(twin.getId()).getStatus())
                .isEqualTo("ORIGINAL_RUNNING_TWIN_ENDED");

        // and it got there by walking, not by being deleted
        assertThat(twinReached(twin, KYC)).isTrue();
        assertThat(twinReached(twin, "Gateway_ParallelSplit")).isTrue();
        assertThat(twinReached(twin, "Gateway_ParallelJoin")).isTrue();
        assertThat(twinReached(twin, NOTIFY)).isTrue();
        assertThat(twinReached(twin, "EndEvent_Success")).isTrue();

        assertThat(workbenchService.completeCurrentTasks(twin.getId())).hasSize(1);
        assertThat(workbenchService.getTwinProcess(twin.getId()).getStatus()).isEqualTo("ENDED");

        // Seven of each, kept apart. The gateways the twin walked through cost nothing because
        // only an activity waits on a message, so the two numbers happen to match on this model - what
        // matters is that they're two counters. Share one and this run spends fourteen slots out
        // of a budget that exists to limit agent requests, and the demo starts being refused
        // halfway through for no reason anybody watching can see.
        assertThat(governanceService.getUsage(twin.getId()).getTwinExecutionCount()).isEqualTo(7);
        assertThat(governanceService.getUsage(twin.getId()).getEvolutionCount()).isEqualTo(7);
    }

    // the delegate is only worth anything if a project's own code is what actually runs, so this
    // is about the bean rather than the token
    @Test
    void theDefaultProjectAutomationRunsAndLeavesProofOnTheTwin() throws IOException {
        ProcessModel model = workbenchService.saveProcessModel(null, "citi wire transfer default automation",
                citibankBpmn());
        TwinProcess twin = workbenchService.launchProcess(model.getId());

        assertThat(twin.getProjectId()).isEqualTo("default");
        workbenchService.connectActivity(twin.getId(), KYC, KYC);
        assertThat(workbenchService.bridgeActivityEvent(twin.getId(), KYC).isApproved()).isTrue();

        // the summary DefaultProjectAutomationService built by dispatching to ValidatorExecutor
        assertThat(twinVariable(twin, "twinAutomation_" + KYC).toString())
                .contains("ValidatorExecutor executed for Task_KYC");
        // and the output it reported, under the naming convention the twin side already uses
        assertThat(twinVariable(twin, "twinAutomationOutput_executor_" + KYC)).isEqualTo("ValidatorExecutor");
        assertThat(twinVariable(twin, "twinAutomationOutput_validationPassed_" + KYC)).isEqualTo(true);

        // an activity the twin has not been moved through has neither
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
        assertThat(twinVariable(twin, "twinAutomationOutput_executor_" + KYC)).isEqualTo("CreditRiskAssessorExecutor");
        assertThat(twinVariable(twin, "twinAutomationOutput_riskFlagged_" + KYC)).isEqualTo(true);
        assertThat(twinVariable(twin, "twinAutomationOutput_riskScore_" + KYC)).isEqualTo(85);
        assertThat(twinVariable(twin, "agentFlaggedRisk")).isEqualTo(true);
    }



    // Governance saying no is a stop, not a failure: the token stays where it is, the caller gets
    // a decision rather than an exception, and the original is untouched either way.
    @Test
    void aDeniedTwinExecutionLeavesTheTokenWhereItIsWithoutThrowing() throws IOException {
        ProcessModel model = workbenchService.saveProcessModel(null, "citi wire transfer twin quota",
                citibankBpmn());
        TwinProcess twin = workbenchService.launchProcess(model.getId());
        workbenchService.connectActivity(twin.getId(), KYC, KYC);

        governanceService.updatePolicy(null, null, 0);

        AgentDecision kyc = workbenchService.bridgeActivityEvent(twin.getId(), KYC);
        // the evolution is a separate budget and still went through
        assertThat(kyc.isApproved()).isTrue();
        assertThat(twinVariable(twin, "evolvedAgent_" + KYC)).isEqualTo(BRIDGE_AGENT);

        assertThat(twinToken(twin)).containsExactly(KYC);
        assertThat(twinVariable(twin, "twinAutomation_" + KYC)).isNull();
        assertThat(twin.getEventLog()).anyMatch(entry -> entry.contains("left parked by governance"));

        // and asking again directly says the same thing, still without throwing
        TwinAdvance blocked = workbenchService.advanceTwinActivity(twin.getId(), KYC);
        assertThat(blocked.isAdvanced()).isFalse();
        assertThat(blocked.getReason()).contains("Twin execution quota exceeded");
        // still waiting on its own message, and still with nothing in the job table
        assertThat(twinToken(twin)).containsExactly(KYC);
        assertThat(managementService.createJobQuery()
                .processInstanceId(twin.getTwinProcessId()).count()).isZero();

        // the human's side never even hears about it
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

        // ids are the whole reason ActivityLink can keep mapping one side to the other. The receive
        // task is the synchronization point and keeps the original's activity id unchanged; the
        // automation that used to hang off its end listener is now a separate service task right
        // after it, sharing the one correlate() command rather than a listener on the same node.
        ReceiveTask credit = generated.getModelElementById(CREDIT);
        assertThat(credit).isNotNull();
        assertThat(credit.getName()).isEqualTo("Assess Credit Risk");
        assertThat(credit.getMessage().getName()).isEqualTo("TwinAdvance_" + CREDIT);
        // the receive task still carries its metaml: declarations (asserted below via
        // outputDeclarations), just no execution listener - that moved to the automation task
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
        // service tasks exist now (the automation step), but none of them sits on a job - that's
        // what lets the job executor stay on exactly as Camunda ships it
        assertThat(Bpmn.convertToString(generated)).doesNotContain("asyncBefore");

        // gateways, conditions and the default flow all come over, or the twin would take a
        // different route through the same diagram
        ExclusiveGateway checks = generated.getModelElementById("Gateway_ChecksPassed");
        assertThat(checks.getDefault()).isNotNull();
        assertThat(checks.getDefault().getId()).isEqualTo("Flow_Checks_Pass");
        SequenceFlow fail = generated.getModelElementById("Flow_Checks_Fail");
        assertThat(fail.getName()).isEqualTo("No");
        assertThat(fail.getConditionExpression().getTextContent())
                .isEqualTo("${execution.getVariable('agentFlaggedRisk') == true}");
        assertThat(element(generated, "Gateway_ParallelSplit")).isNotNull();
        assertThat(element(generated, "EndEvent_RejectedIdentity")).isNotNull();

        // the boundary timer is left out on purpose, and its escalation branch goes with it
        // because nothing else led there
        assertThat(element(generated, "BoundaryEvent_Timeout")).isNull();
        assertThat(element(generated, "Task_EscalateTimeout")).isNull();
        assertThat(element(generated, "EndEvent_EscalatedTimeout")).isNull();

        // metaml declarations survive the trip, which the reader that already knows how to find
        // them can confirm against the generated definition directly
        assertThat(outputDeclarations.forActivity(twin.getTwinProcessDefinitionId(), CREDIT))
                .containsEntry("riskFlagged", "agentFlaggedRisk");
        String xml = Bpmn.convertToString(generated);
        assertThat(xml).contains("identityDocumentType").contains("transferAmount");

        // and the twin is its own process key, not a second version of the original's. Through
        // getKey() rather than string-prefix-matching getTwinProcessDefinitionId() itself: proven
        // empirically (ZzDefinitionIdProbeTest, deleted after recording the finding in
        // PROF_QA_PREP.md) that ProcessDefinition.getId() sometimes comes back as a bare UUID
        // instead of "key:version:deploymentId" - a narrow, purely cosmetic Camunda quirk with no
        // functional effect (nothing in this codebase parses that string; it's always used as an
        // opaque handle), but it makes the id's format the wrong thing to assert on here.
        assertThat(repositoryService.getProcessDefinition(twin.getTwinProcessDefinitionId()).getKey())
                .isEqualTo("Process_WireTransfer_twin");
    }

    // A multi-instance activity's receive task deliberately keeps the
    // original activity's id, but lives inside a generator-built wrapper sub-process. moveToNode()
    // is scope-blind, so a later flow leaving that same activity used to find the nested receive
    // task and keep building from inside the wrapper - nesting the entire rest of the process inside
    // the multi-instance loop and leaving the receive task with a second, illegitimate outgoing
    // flow. Reproduced against grad-admission-review.bpmn's own Task_CommitteeReview, whose
    // downstream (Gateway_MajorityApproved onward) is exactly this shape.
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

    // the twin only walks the branch its own conditions choose, and agentFlaggedRisk is written on
    // the original by AgentExecutionDelegate and never on the twin. Worth pinning down so the gap
    // is a recorded limitation rather than something rediscovered in a demo.
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

        // the original escalated, the twin carried on down its default flow, and there is no twin
        // job at Task_Escalate for the trigger to find
        assertThat(openTasks(twin)).containsExactly(ESCALATE);
        assertThat(twinToken(twin)).containsExactly(APPROVE);
        assertThat(workbenchService.advanceTwinActivity(twin.getId(), ESCALATE).isAdvanced()).isFalse();
    }

    // Every launch used to make a fresh twin deployment with a fresh version of the twin
    // definition, and nothing ever removed the old ones, so a morning of demoing left cockpit full
    // of them. Duplicate filtering off a deployment name derived from the model is what stops it.
    @Test
    void relaunchingTheSameModelReusesTheTwinDeployment() throws IOException {
        ProcessModel model = workbenchService.saveProcessModel(null, "citi wire transfer relaunched",
                citibankBpmn());

        TwinProcess first = workbenchService.launchProcess(model.getId());
        long deploymentsAfterFirst = repositoryService.createDeploymentQuery().count();

        TwinProcess second = workbenchService.launchProcess(model.getId());

        assertThat(repositoryService.createDeploymentQuery().count()).isEqualTo(deploymentsAfterFirst);
        assertThat(second.getTwinProcessDefinitionId()).isEqualTo(first.getTwinProcessDefinitionId());
        // two twins all the same, each with its own pair of live instances
        assertThat(second.getTwinProcessId()).isNotEqualTo(first.getTwinProcessId());
        assertThat(twinToken(first)).containsExactly(KYC);
        assertThat(twinToken(second)).containsExactly(KYC);
    }

    // connect used to check the twin activity id against the original's definition, which passed
    // for the wrong reason because the two share ids. Task_EscalateTimeout is the one activity in
    // the citi model that exists on the original and not on the twin, the boundary event having
    // taken its whole branch with it, so it is the id that tells the two checks apart.
    @Test
    void connectRejectsATwinActivityTheGeneratorLeftOut() throws IOException {
        ProcessModel model = workbenchService.saveProcessModel(null, "citi wire transfer bad twin link",
                citibankBpmn());
        TwinProcess twin = workbenchService.launchProcess(model.getId());

        assertThatThrownBy(() -> workbenchService.connectActivity(twin.getId(), KYC, "Task_EscalateTimeout"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("twinActivityId");
        // and the original's own side of the link is still checked against the original
        assertThatThrownBy(() -> workbenchService.connectActivity(twin.getId(), "Task_NotInTheDiagram", KYC))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("originalActivityId");
    }

    // EvolvedAgent_<twinActivityId> and the twin's own advance message
    // are both keyed on twinActivityId alone, so two original activities sharing one twin activity
    // would silently clobber each other's agent instead of each getting its own. connectActivity is
    // the only place links are created, so it's the one place that has to refuse this.
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
        // the rejected attempt left the original link exactly as it was
        assertThat(workbenchService.getTwinProcess(twin.getId()).findTwinActivityId(KYC)).contains(KYC);
        assertThat(workbenchService.getTwinProcess(twin.getId()).findTwinActivityId(AML)).isEmpty();

        // re-pointing KYC's own link elsewhere still works (one-to-one from the original's side was
        // never in question) and frees up twin activity KYC for someone else to claim
        workbenchService.connectActivity(twin.getId(), KYC, AML);
        assertThat(workbenchService.getTwinProcess(twin.getId()).findTwinActivityId(KYC)).contains(AML);
        workbenchService.connectActivity(twin.getId(), AML, KYC);
        assertThat(workbenchService.getTwinProcess(twin.getId()).findTwinActivityId(AML)).contains(KYC);
    }

    // 
    // the check (stream/filter/findFirst) and the mutation (removeIf + add) are three separate
    // CopyOnWriteArrayList operations - thread-safe individually, but not as a sequence - so two
    // concurrent calls connecting different originals to the same still-unclaimed twin activity
    // could both pass the check before either one's add() became visible to the other. Fixed with a
    // per-twin lock; this proves it holds under an actual race, not just the single-threaded case
    // the test above covers.
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
            // never both - one wins the twin activity, the other is rejected as many-to-one
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

    // An
    // activity id ending in one of the generator's own reserved suffixes collides with its own
    // derived twin ids ("Task_A_automate" next to "Task_A"'s derived automation task, both named
    // "Task_A_automate") and used to fail deployment with an opaque duplicate-id error instead of
    // a clear one
    @Test
    void anActivityIdEndingInAReservedSuffixIsRejectedWithAClearReason() throws IOException {
        ProcessModel model = workbenchService.saveProcessModel(null, "reserved suffix test",
                reservedSuffixBpmn());
        assertThatThrownBy(() -> workbenchService.launchProcess(model.getId()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Task_A_automate");
    }

    // same review: a sequential multi-instance activity can have any EL expression as its loop
    // cardinality, not just a literal number - the twin has no such variable to evaluate it
    // against, so it has to fall back to a single visit rather than carry the expression over
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

    // A literal completionCondition on a multi-instance activity used
    // to be silently dropped with no warning at all, unlike the sibling non-literal-cardinality
    // fallback right above, which does warn - a genuine, silent multi-instance completion-semantics
    // divergence from what the original's own definition specifies.
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

    // Inclusive Gateway gets the same fail-fast treatment as every other unsupported construct.
    // The generator copies conditions verbatim, so the twin's split evaluates them against
    // TWIN-local variables and can activate a different branch set than the original's split did.
    // Exclusive Gateway is self-limiting and Parallel Gateway has no data-dependence, but an
    // Inclusive Gateway combines join synchronization with data-dependence, so that mismatch
    // deadlocks rather than completing on a wrong path. Supporting it safely would require the
    // bridge to communicate which flows the original's gateway actually took.
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

    // The generic case behind W2: an original activity type the generator has no rule for used to
    // be a warn log and a silent drop of everything only reachable through it, which is exactly the
    // "developer unknowingly deploys a partial twin" the review called out. Now it fails generation
    // loudly instead, naming the offending activity and its type.
    @Test
    void generatingATwinFailsFastOnAnUnsupportedConstructInsteadOfSilentlyDroppingIt() throws IOException {
        ProcessModel model = workbenchService.saveProcessModel(null, "unsupported construct test",
                unsupportedServiceTaskBpmn());

        assertThatThrownBy(() -> workbenchService.launchProcess(model.getId()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Task_AutoStep")
                .hasMessageContaining("does not support");
    }

    // getModelElementById infers its own return type, and assertThat has an overload for enough
    // shapes that the compiler can't pick one. Pinning it to ModelElementInstance settles it.
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

    // Where the twin's token actually is. A receive task holds it as an activity instance;
    // transition instances are collected too so this keeps working if anything asynchronous is
    // ever added to a generated twin.
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

    // through history rather than runtimeService: the twin reaches its end event before the
    // original does, and reading a variable off a finished instance throws
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

    // same walk up to examples/ the other two walkthroughs do
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

    // Was a serviceTask until service tasks became supported (the twin generator now has to cope
    // with whatever a real manufacturing process contains, not just the curated user-task models it
    // was first written for). A businessRuleTask keeps this test doing its actual job: proving an
    // activity type with no rule fails generation loudly instead of being silently dropped.
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
