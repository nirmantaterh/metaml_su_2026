package com.metaml.wbapi;

import com.metaml.workbench.capability.runtime.DelegateExecutionContext;
import org.camunda.bpm.engine.HistoryService;
import org.camunda.bpm.engine.ManagementService;
import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.camunda.bpm.engine.history.HistoricVariableInstance;
import org.camunda.bpm.engine.runtime.Incident;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.metaml.workbench.automation.AutomationResult;
import com.metaml.workbench.automation.ProjectAutomationService;
import com.metaml.workbench.automation.ValidatorExecutor;
import com.metaml.workbench.capability.CapabilityContract;
import com.metaml.workbench.capability.CapabilityProvider;
import com.metaml.workbench.capability.ExecutionMode;
import com.metaml.workbench.capability.IoDeclaration;
import com.metaml.workbench.capability.IoType;
import com.metaml.workbench.capability.runtime.CapabilityOutputContractSource;
import com.metaml.workbench.client.AgentAvailabilityResult;
import com.metaml.workbench.client.NodeManagerClient;
import com.metaml.workbench.model.AgentDecision;
import com.metaml.workbench.model.ProcessModel;
import com.metaml.workbench.model.TwinAdvance;
import com.metaml.workbench.model.TwinProcess;
import com.metaml.workbench.service.GovernanceService;
import com.metaml.workbench.service.WorkbenchService;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

// MetaML Scope 6, Phase 4, exercised through the real runtime rather than around it: a real twin
// process instance, the real TwinAutomationDelegate, real Camunda gateway evaluation, and the real
// ADR-008 failure mechanism.
//
// Everything is generic on purpose - synthetic activity ids, a synthetic provider type, and output
// names that mean nothing to any particular enterprise process.
@IsolatedWorkbenchTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:metaml-capability-output-contract-test;DB_CLOSE_DELAY=-1"
})
class CapabilityOutputContractIntegrationTest {

    private static final String TASK_A = "Task_A";
    private static final String TASK_MI = "Task_MI";
    private static final String PROVIDER_TYPE = "generic-provider";
    private static final String PROVIDER_ID = PROVIDER_TYPE + "-agent-01";

    @MockitoBean
    private NodeManagerClient nodeManagerClient;
    // Overrides the DefaultProjectAutomationService bean so this test controls the actual execution
    // result a provider returns, without touching any production executor.
    @MockitoBean(name = "default")
    private ProjectAutomationService defaultAutomation;
    // No implementation of this is registered in production (see CapabilityOutputContractSource);
    // registering one here is what puts a declared capability contract in front of the boundary.
    @MockitoBean
    private CapabilityOutputContractSource outputContractSource;

    @Autowired
    private WorkbenchService workbenchService;
    @Autowired
    private GovernanceService governanceService;
    @Autowired
    private RuntimeService runtimeService;
    @Autowired
    private HistoryService historyService;
    @Autowired
    private ManagementService managementService;

    @BeforeEach
    void stubTheCatalogAndOpenTheQuotas() {
        given(nodeManagerClient.checkAgentAvailability(anyString())).willAnswer(call -> {
            String type = call.getArgument(0);
            return new AgentAvailabilityResult(type, true, type + "-agent-01", "stub catalog", false);
        });
        governanceService.updatePolicy(Set.of(), 50, 200);
    }

    // ------------------------------------------------------- N. GATEWAY CONTINUATION (A -> ? -> B)

    @Test
    void aValidatedProviderOutputBecomesAProcessVariableThatSelectsTheGatewayBranch() throws IOException {
        bindContract(declaration("decision", IoType.BOOLEAN));
        givenOutputs(Map.of("decision", true));

        TwinProcess twin = launch("gateway continuation true", gatewayBpmn());
        advance(twin);

        // provider output -> contract validation -> process variable -> gateway -> selected path
        assertThat(twinVariable(twin, "decision")).isEqualTo(true);
        assertThat(twinReached(twin, "Task_B")).isTrue();
        assertThat(twinReached(twin, "Task_C")).isFalse();
    }

    @Test
    void theSameBoundaryDrivesTheOtherBranchWhenTheProviderComputesTheOtherValue() throws IOException {
        bindContract(declaration("decision", IoType.BOOLEAN));
        givenOutputs(Map.of("decision", false));

        TwinProcess twin = launch("gateway continuation false", gatewayBpmn());
        advance(twin);

        assertThat(twinVariable(twin, "decision")).isEqualTo(false);
        assertThat(twinReached(twin, "Task_C")).isTrue();
        assertThat(twinReached(twin, "Task_B")).isFalse();
    }

    // ------------------------------------------------- O. FAILURE PATH + 29. ATOMICITY IN PROCESS STATE

    @Test
    void anUndeclaredOutputFailsTheExecutionThroughTheExistingIncidentMechanism() throws IOException {
        bindContract(declaration("decision", IoType.BOOLEAN));
        givenOutputs(ordered("decision", true, "undeclaredExtra", "surprise"));

        TwinProcess twin = launch("undeclared output", gatewayBpmn());

        assertThatThrownBy(() -> workbenchService.advanceTwinActivity(twin.getId(), TASK_A))
                .hasMessageContaining("UNDECLARED_OUTPUT")
                .hasMessageContaining("undeclaredExtra");

        assertIncidentRaisedAndNothingAdvanced(twin, "UNDECLARED_OUTPUT");

        // Atomicity at process-state level: neither the valid output nor the invalid one, in either
        // its bare or its per-visit form, exists anywhere in the process.
        assertThat(twinVariable(twin, "decision")).isNull();
        assertThat(twinVariable(twin, "twinAutomationOutput_decision_" + TASK_A + "_0")).isNull();
        assertThat(twinVariable(twin, "twinAutomationOutput_undeclaredExtra_" + TASK_A + "_0")).isNull();
        assertThat(twinVariable(twin, "twinAutomation_" + TASK_A + "_0")).isNull();
    }

    @Test
    void aMissingDeclaredOutputFailsTheExecutionAndCommitsNothing() throws IOException {
        bindContract(declaration("decision", IoType.BOOLEAN), declaration("detail", IoType.STRING));
        givenOutputs(Map.of("decision", true));

        TwinProcess twin = launch("missing declared output", gatewayBpmn());

        assertThatThrownBy(() -> workbenchService.advanceTwinActivity(twin.getId(), TASK_A))
                .hasMessageContaining("MISSING_DECLARED_OUTPUT")
                .hasMessageContaining("detail");

        assertIncidentRaisedAndNothingAdvanced(twin, "MISSING_DECLARED_OUTPUT");
        assertThat(twinVariable(twin, "decision")).isNull();
        assertThat(twinVariable(twin, "twinAutomationOutput_decision_" + TASK_A + "_0")).isNull();
    }

    // -------------------------------------------------------------- J. ATOMIC FAILURE, THREE OUTPUTS

    @Test
    void twoValidOutputsAreNotCommittedToProcessStateWhenAThirdIsTypeInvalid() throws IOException {
        bindContract(declaration("a", IoType.BOOLEAN), declaration("b", IoType.NUMBER),
                declaration("c", IoType.BOOLEAN));
        givenOutputs(ordered("a", true, "b", 5, "c", "not a boolean"));

        TwinProcess twin = launch("atomic failure", gatewayBpmn());

        assertThatThrownBy(() -> workbenchService.advanceTwinActivity(twin.getId(), TASK_A))
                .hasMessageContaining("TYPE_MISMATCH");

        assertIncidentRaisedAndNothingAdvanced(twin, "TYPE_MISMATCH");
        assertThat(twinVariable(twin, "twinAutomationOutput_a_" + TASK_A + "_0")).isNull();
        assertThat(twinVariable(twin, "twinAutomationOutput_b_" + TASK_A + "_0")).isNull();
        assertThat(twinVariable(twin, "twinAutomationOutput_c_" + TASK_A + "_0")).isNull();
    }

    // ---------------------------------------------------------- Q. INVALID TEST PROVIDER (generic)

    @Test
    void aProviderThatDeclaresABooleanButReturnsAStringIsRejectedAsATypeMismatch() throws IOException {
        // Generic test double, not a production business provider: declares one BOOLEAN output and
        // returns the string "yes" for it.
        bindContract(declaration("validationPassed", IoType.BOOLEAN));
        givenOutputs(Map.of("validationPassed", "yes"));

        TwinProcess twin = launch("invalid provider type", gatewayBpmn());

        assertThatThrownBy(() -> workbenchService.advanceTwinActivity(twin.getId(), TASK_A))
                .hasMessageContaining("TYPE_MISMATCH")
                .hasMessageContaining("declaredType=BOOLEAN")
                .hasMessageContaining("actualType=String");
        assertIncidentRaisedAndNothingAdvanced(twin, "TYPE_MISMATCH");
    }

    @Test
    void aProviderThatReturnsAnEntirelyDifferentOutputNameIsRejectedAsUndeclared() throws IOException {
        bindContract(declaration("validationPassed", IoType.BOOLEAN));
        givenOutputs(Map.of("unexpectedOutput", true));

        TwinProcess twin = launch("invalid provider name", gatewayBpmn());

        assertThatThrownBy(() -> workbenchService.advanceTwinActivity(twin.getId(), TASK_A))
                .hasMessageContaining("UNDECLARED_OUTPUT")
                .hasMessageContaining("MISSING_DECLARED_OUTPUT");
        assertIncidentRaisedAndNothingAdvanced(twin, "UNDECLARED_OUTPUT");
    }

    // ------------------------------------------------------------------------ K. EMPTY CONTRACT

    @Test
    void aProviderDeclaringNoOutputsAtAllIsPermittedNoOutputsAtAll() throws IOException {
        bindContract();
        givenOutputs(Map.of("foo", true));

        TwinProcess twin = launch("empty contract", gatewayBpmn());

        assertThatThrownBy(() -> workbenchService.advanceTwinActivity(twin.getId(), TASK_A))
                .hasMessageContaining("UNDECLARED_OUTPUT")
                .hasMessageContaining("foo");
        assertIncidentRaisedAndNothingAdvanced(twin, "UNDECLARED_OUTPUT");
        assertThat(twinVariable(twin, "twinAutomationOutput_foo_" + TASK_A + "_0")).isNull();
    }

    // ---------------------------------------------------------------------- P. REAL PROVIDER

    // The real ValidatorExecutor, unmodified, behind a contract that truthfully declares what it
    // actually produces. ValidatorExecutor returns only its four legitimate business outputs
    // (executor identity now lives solely in AutomationResult.summary(), not in outputs()), which is
    // exactly what the node manager catalog authors as this provider's produced-outputs - see
    // CatalogCapabilityOutputContractSource, the production CapabilityOutputContractSource wired
    // against that same catalog.
    @Test
    void theRealValidatorExecutorSatisfiesAContractThatTruthfullyDeclaresItsOutputs() throws IOException {
        bindContract(
                declaration("validationPassed", IoType.BOOLEAN),
                declaration("schemaVersion", IoType.STRING),
                declaration("validationStatus", IoType.STRING),
                declaration("validationMessage", IoType.STRING));

        ValidatorExecutor validator = new ValidatorExecutor();
        given(defaultAutomation.execute(any(DelegateExecution.class)))
                .willAnswer(call -> validator.execute(
                        new DelegateExecutionContext(call.getArgument(0)), TASK_A, PROVIDER_ID));

        TwinProcess twin = launch("real provider", gatewayBpmn("validationPassed"));
        advance(twin);

        // real provider contract -> real executor -> real output -> P4 validation -> process
        // variable -> gateway -> selected path
        assertThat(twinVariable(twin, "validationPassed")).isEqualTo(true);
        assertThat(twinVariable(twin, "twinAutomationOutput_schemaVersion_" + TASK_A + "_0"))
                .isEqualTo(ValidatorExecutor.SCHEMA_VERSION);
        assertThat(twinReached(twin, "Task_B")).isTrue();
        assertThat(twinReached(twin, "Task_C")).isFalse();
    }

    // ------------------------------------------------------- L/M. MULTI-INSTANCE VISIT ISOLATION

    @Test
    void twoVisitsOfOneMultiInstanceActivityAreValidatedAndRecordedIndependently() throws IOException {
        bindContract(declaration("decision", IoType.BOOLEAN));
        List<String> activityInstanceIds = new ArrayList<>();
        // visit 0 computes true, visit 1 computes false
        given(defaultAutomation.execute(any(DelegateExecution.class)))
                .willAnswer(call -> {
                    activityInstanceIds.add(
                            ((DelegateExecution) call.getArgument(0)).getActivityInstanceId());
                    return new AutomationResult("visit " + (activityInstanceIds.size() - 1),
                            Map.of("decision", activityInstanceIds.size() == 1));
                });

        ProcessModel model = workbenchService.saveProcessModel(null, "multi-instance isolation",
                sequentialMultiInstanceBpmn());
        TwinProcess twin = workbenchService.launchProcess(model.getId());
        workbenchService.connectActivity(twin.getId(), TASK_MI, TASK_MI);

        assertThat(workbenchService.evolveActivity(twin.getId(), TASK_MI, PROVIDER_TYPE).isApproved())
                .isTrue();
        assertThat(workbenchService.advanceTwinActivity(twin.getId(), TASK_MI).isAdvanced()).isTrue();
        assertThat(workbenchService.evolveActivity(twin.getId(), TASK_MI, PROVIDER_TYPE).isApproved())
                .isTrue();
        assertThat(workbenchService.advanceTwinActivity(twin.getId(), TASK_MI).isAdvanced()).isTrue();

        // Each visit was validated against the contract on its own activity instance, and the two
        // instances really are distinct rather than one instance visited twice.
        assertThat(activityInstanceIds).hasSize(2);
        assertThat(activityInstanceIds.get(0)).isNotEqualTo(activityInstanceIds.get(1));

        // Isolation: the loop counter is part of the per-visit variable name, so visit 1 wrote under
        // its own name and neither collapsed into a single shared, unsuffixed variable.
        assertThat(twinVariable(twin, "twinAutomationOutput_decision_" + TASK_MI + "_1")).isEqualTo(false);
        assertThat(twinVariable(twin, "twinAutomationOutput_decision_" + TASK_MI)).isNull();
        assertThat(twinVariable(twin, "twinAutomation_" + TASK_MI + "_1")).isEqualTo("visit 1");
        assertThat(twinVariable(twin, "twinAutomation_" + TASK_MI)).isNull();
    }

    // ------------------------------------------------------------------------------- helpers

    private void assertIncidentRaisedAndNothingAdvanced(TwinProcess twin, String expectedKind) {
        Incident incident = runtimeService.createIncidentQuery()
                .processInstanceId(twin.getTwinProcessId()).singleResult();
        assertThat(incident).isNotNull();
        // the repository's existing ADR-008 synchronous-automation failure mechanism, not a new one
        assertThat(incident.getIncidentType()).isEqualTo("twinAutomationFailure");
        assertThat(incident.getIncidentMessage()).contains(expectedKind).contains(PROVIDER_ID);

        // The token did not move past the failure and the external task was never completed
        // successfully: the twin is still parked on its receive task with its subscription intact.
        assertThat(runtimeService.getActiveActivityIds(twin.getTwinProcessId())).contains(TASK_A);
        assertThat(runtimeService.createEventSubscriptionQuery()
                .processInstanceId(twin.getTwinProcessId()).count()).isEqualTo(1);

        // ADR-009: no job executor involvement, so no retry policy of any kind applies here.
        assertThat(managementService.createJobQuery()
                .processInstanceId(twin.getTwinProcessId()).count()).isZero();
    }

    private TwinProcess launch(String name, String bpmn) throws IOException {
        ProcessModel model = workbenchService.saveProcessModel(null, name, bpmn);
        TwinProcess twin = workbenchService.launchProcess(model.getId());
        workbenchService.connectActivity(twin.getId(), TASK_A, TASK_A);
        AgentDecision evolved = workbenchService.evolveActivity(twin.getId(), TASK_A, PROVIDER_TYPE);
        assertThat(evolved.isApproved()).isTrue();
        assertThat(evolved.getAgentName()).isEqualTo(PROVIDER_ID);
        return twin;
    }

    private void advance(TwinProcess twin) {
        TwinAdvance advance = workbenchService.advanceTwinActivity(twin.getId(), TASK_A);
        assertThat(advance.isAdvanced()).isTrue();
    }

    private void givenOutputs(Map<String, Object> outputs) {
        given(defaultAutomation.execute(any(DelegateExecution.class)))
                .willReturn(new AutomationResult("generic provider ran", outputs));
    }

    private void bindContract(IoDeclaration... producedOutputs) {
        CapabilityProvider provider = new CapabilityProvider(PROVIDER_ID, PROVIDER_TYPE, "1.0.0",
                new CapabilityContract(null, Set.of(),
                        new LinkedHashSet<>(List.of(producedOutputs)),
                        ExecutionMode.SYNCHRONOUS, Map.of(), Set.of()),
                "a generic synthetic provider", true, null);
        given(outputContractSource.providerFor(anyString())).willReturn(Optional.empty());
        given(outputContractSource.providerFor(PROVIDER_ID)).willReturn(Optional.of(provider));
    }

    private static IoDeclaration declaration(String name, IoType type) {
        return new IoDeclaration(name, type, true);
    }

    private static Map<String, Object> ordered(Object... keysAndValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            map.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return map;
    }

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

    // Task A -> gateway -> Task B on true, Task C otherwise. Nothing here is specific to any
    // particular business process.
    //
    // Task A carries a literal-cardinality multi-instance characteristic - the smallest one there
    // is, a single visit - purely so the twin generator wraps it in a subprocess. That wrapper is
    // what makes the shape deterministic: a plain twin activity leaves its receive task by two
    // concurrent flows (one to its automation task, one to whatever followed it in the original),
    // so a gateway sitting immediately downstream can be evaluated before the automation task has
    // run at all. That is pre-existing twin-generator topology (TwinModelGenerator.exitNodeId), not
    // anything this phase introduces or changes.
    private static String gatewayBpmn() {
        return gatewayBpmn("decision");
    }

    private static String gatewayBpmn(String conditionVariable) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                                   xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                                   id="Definitions_OutputContract" targetNamespace="http://metaml.com/test">
                  <bpmn:process id="Process_OutputContract" isExecutable="true">
                    <bpmn:startEvent id="StartEvent_1">
                      <bpmn:outgoing>Flow_1</bpmn:outgoing>
                    </bpmn:startEvent>
                    <bpmn:userTask id="Task_A" name="Task A">
                      <bpmn:incoming>Flow_1</bpmn:incoming>
                      <bpmn:outgoing>Flow_2</bpmn:outgoing>
                      <bpmn:multiInstanceLoopCharacteristics isSequential="true">
                        <bpmn:loopCardinality>1</bpmn:loopCardinality>
                      </bpmn:multiInstanceLoopCharacteristics>
                    </bpmn:userTask>
                    <bpmn:exclusiveGateway id="Gateway_Decision" default="Flow_False">
                      <bpmn:incoming>Flow_2</bpmn:incoming>
                      <bpmn:outgoing>Flow_True</bpmn:outgoing>
                      <bpmn:outgoing>Flow_False</bpmn:outgoing>
                    </bpmn:exclusiveGateway>
                    <bpmn:userTask id="Task_B" name="Task B">
                      <bpmn:incoming>Flow_True</bpmn:incoming>
                      <bpmn:outgoing>Flow_B_End</bpmn:outgoing>
                    </bpmn:userTask>
                    <bpmn:userTask id="Task_C" name="Task C">
                      <bpmn:incoming>Flow_False</bpmn:incoming>
                      <bpmn:outgoing>Flow_C_End</bpmn:outgoing>
                    </bpmn:userTask>
                    <bpmn:endEvent id="EndEvent_1">
                      <bpmn:incoming>Flow_B_End</bpmn:incoming>
                      <bpmn:incoming>Flow_C_End</bpmn:incoming>
                    </bpmn:endEvent>
                    <bpmn:sequenceFlow id="Flow_1" sourceRef="StartEvent_1" targetRef="Task_A" />
                    <bpmn:sequenceFlow id="Flow_2" sourceRef="Task_A" targetRef="Gateway_Decision" />
                    <bpmn:sequenceFlow id="Flow_True" sourceRef="Gateway_Decision" targetRef="Task_B">
                      <bpmn:conditionExpression xsi:type="bpmn:tFormalExpression">${CONDITION_VARIABLE}</bpmn:conditionExpression>
                    </bpmn:sequenceFlow>
                    <bpmn:sequenceFlow id="Flow_False" sourceRef="Gateway_Decision" targetRef="Task_C" />
                    <bpmn:sequenceFlow id="Flow_B_End" sourceRef="Task_B" targetRef="EndEvent_1" />
                    <bpmn:sequenceFlow id="Flow_C_End" sourceRef="Task_C" targetRef="EndEvent_1" />
                  </bpmn:process>
                </bpmn:definitions>
                """.replace("CONDITION_VARIABLE", conditionVariable);
    }

    private static String sequentialMultiInstanceBpmn() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                                   id="Definitions_OutputContractMi" targetNamespace="http://metaml.com/test">
                  <bpmn:process id="Process_OutputContractMi" isExecutable="true">
                    <bpmn:startEvent id="StartEvent_1">
                      <bpmn:outgoing>Flow_1</bpmn:outgoing>
                    </bpmn:startEvent>
                    <bpmn:userTask id="Task_MI" name="Task MI">
                      <bpmn:incoming>Flow_1</bpmn:incoming>
                      <bpmn:outgoing>Flow_2</bpmn:outgoing>
                      <bpmn:multiInstanceLoopCharacteristics isSequential="true">
                        <bpmn:loopCardinality>2</bpmn:loopCardinality>
                      </bpmn:multiInstanceLoopCharacteristics>
                    </bpmn:userTask>
                    <bpmn:endEvent id="EndEvent_1">
                      <bpmn:incoming>Flow_2</bpmn:incoming>
                    </bpmn:endEvent>
                    <bpmn:sequenceFlow id="Flow_1" sourceRef="StartEvent_1" targetRef="Task_MI" />
                    <bpmn:sequenceFlow id="Flow_2" sourceRef="Task_MI" targetRef="EndEvent_1" />
                  </bpmn:process>
                </bpmn:definitions>
                """;
    }
}
