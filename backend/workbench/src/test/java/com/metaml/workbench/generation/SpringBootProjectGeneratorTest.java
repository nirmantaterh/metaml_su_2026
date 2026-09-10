package com.metaml.workbench.generation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.metaml.workbench.bpmn.TwinModelGenerator;
import com.metaml.workbench.codegen.DelegateClassGenerator;
import com.metaml.workbench.codegen.GeneratedDelegate;

class SpringBootProjectGeneratorTest {

    @TempDir
    Path tempDir;

    private Path templateDir;
    private Path outputDir;

    // Lightweight template directory fixture for file structure and copy testing.
    @BeforeEach
    void buildFakeTemplate() throws IOException {
        templateDir = tempDir.resolve("template");
        outputDir = tempDir.resolve("output");

        write(templateDir.resolve("pom.xml"), "<project>fake pom</project>");
        write(templateDir.resolve("src/main/resources/processes/loanApproval.bpmn"), "<bpmn>placeholder demo</bpmn>");
        write(templateDir.resolve("src/main/java/com/example/camundademo/delegate/CalculateInterestService.java"),
                "placeholder delegate");
        write(templateDir.resolve("src/main/java/com/example/camundademo/controller/Camundacontroller.java"),
                "placeholder controller, hardcoded to loanApproval");
        write(templateDir.resolve("src/main/java/com/example/camundademo/context/LoanApplicationContext.java"),
                "placeholder, inert in the real template too");
        write(templateDir.resolve(
                        "src/main/java/com/example/camundademo/utils/restmappings/BPMNProcessRESTMappings.java"),
                "placeholder mappings");
        write(templateDir.resolve("src/main/java/com/example/camundademo/security/WebSecurityConfig.java"),
                "unrelated file that must survive the copy untouched");
    }

    private SpringBootProjectGenerator generator() {
        return new SpringBootProjectGenerator(templateDir.toString(), outputDir.toString(),
                new TwinModelGenerator(), new DelegateClassGenerator(),
                new com.metaml.workbench.codegen.ExternalTaskWorkerGenerator());
    }

    // Resolves expected package path using the slug derived from the process key.
    private static String manufacturingPackagePath(String processKey) {
        return "src/main/java/com/metaml/targetplatform/" + processKey.toLowerCase().replaceAll("[^a-z0-9]", "");
    }

    @Test
    void generatesAProjectDirectoryContainingTheRealBpmnNamedAfterItsProcessKey() {
        GeneratedProject project = generator().generate(loanApprovalBpmn(), List.of());

        assertThat(project.processKey()).isEqualTo("loanApproval");
        Path bpmnFile = project.directory().resolve("src/main/resources/processes/loanApproval.bpmn");
        assertThat(bpmnFile).exists();
        assertThat(readString(bpmnFile)).isEqualTo(loanApprovalBpmn());
    }

    @Test
    void removesTheTemplatesOwnPlaceholderDemoContentSoItCantDeployAlongsideTheRealOne() {
        GeneratedProject project = generator().generate(loanApprovalBpmn(), List.of());

        assertThat(project.directory().resolve(
                "src/main/java/com/example/camundademo/delegate/CalculateInterestService.java")).doesNotExist();
        assertThat(project.directory().resolve(
                "src/main/java/com/example/camundademo/controller/Camundacontroller.java")).doesNotExist();
        assertThat(project.directory().resolve(
                "src/main/java/com/example/camundademo/context/LoanApplicationContext.java")).doesNotExist();
        assertThat(project.directory().resolve(
                "src/main/java/com/example/camundademo/utils/restmappings/BPMNProcessRESTMappings.java"))
                .doesNotExist();
    }

    // Controller generation creates specific completion endpoints for each eligible activity rather than a single generic endpoint.
    @Test
    void writesAGeneratedControllerBuiltAroundTheActualProcessKeyNotHardcodedToLoanApproval() {
        GeneratedProject project = generator().generate(differentProcessKeyBpmn(), List.of());

        String source = readString(controllerOf(project));
        assertThat(source).contains("startProcessInstanceByKey(\"gradAdmission\")");
        assertThat(source).contains("@PostMapping(\"/start\")");
    }

    // Verifies the generic completion endpoint is omitted in favor of per-activity endpoints.
    @Test
    void noLongerGeneratesTheGenericCompletionEndpointEveryActivityUsedToShare() {
        GeneratedProject project = generator().generate(fourActivityBpmn(), List.of());

        String source = readString(controllerOf(project));
        assertThat(source).doesNotContain("complete-task");
        assertThat(source).doesNotContain("public ResponseEntity<Map<String, List<String>>> completeTask(");
    }

    // Eligible activities produce activity-specific completion endpoints.
    @Test
    void generatesOneCompletionEndpointPerBpmnActivity() {
        GeneratedProject project = generator().generate(fourActivityBpmn(), List.of());

        String source = readString(controllerOf(project));
        assertThat(source).contains("@PostMapping(\"/{processInstanceId}/review-application/complete\")");
        assertThat(source).contains("@PostMapping(\"/{processInstanceId}/credit-check/complete\")");
        assertThat(source).contains("@PostMapping(\"/{processInstanceId}/fraud-review/complete\")");
        assertThat(source).contains("@PostMapping(\"/{processInstanceId}/approve-application/complete\")");
        assertThat(countOccurrences(source, "/complete\")")).isEqualTo(4);
    }

    // Each endpoint dispatches on its own activity's BPMN element id. The slug only appears in
    // the URL; the handler passes the literal element id to the task query.
    @Test
    void eachEndpointCompletesItsOwnActivityAndNotAnother() {
        GeneratedProject project = generator().generate(fourActivityBpmn(), List.of());

        String source = readString(controllerOf(project));
        assertThat(source).contains("""
                    @PostMapping("/{processInstanceId}/credit-check/complete")
                    public ResponseEntity<Map<String, List<String>>> completeCreditCheck(@PathVariable String processInstanceId) {
                        ResponseEntity<Map<String, List<String>>> response = completeUserTask(processInstanceId, "Task_CreditCheck");
                        notificationBridge.notifyTwin(processInstanceId, "Task_CreditCheck");
                        return response;
                    }
                """);
        assertThat(source).contains(".taskDefinitionKey(activityId)");
    }

    // the generalization: eligibility is decided by how the activity actually executes, not by
    // its element type. Two serviceTasks, same element type, opposite answers - the external one
    // is a wait state the engine will never advance, the delegateExpression one runs on arrival.
    @Test
    void eligibilityFollowsExecutionSemanticsRatherThanElementType() {
        GeneratedProject project = generator().generate(externalAndInlineServiceTaskBpmn(), List.of());

        String source = readString(controllerOf(project));
        assertThat(source).contains("@PostMapping(\"/{processInstanceId}/charge-card/complete\")");
        assertThat(source).contains("completeExternalTask(processInstanceId, \"Task_Charge\");");
        assertThat(source).doesNotContain("calculate-interest");
        assertThat(countOccurrences(source, "/complete\")")).isEqualTo(1);
    }

    // a receive task has no Task row behind it, so it cannot go through user-task completion -
    // the parked execution is what gets signalled
    @Test
    void receiveTasksAreTriggeredBySignallingTheParkedExecution() {
        GeneratedProject project = generator().generate(receiveTaskBpmn(), List.of());

        String source = readString(controllerOf(project));
        assertThat(source).contains("@PostMapping(\"/{processInstanceId}/await-settlement/complete\")");
        assertThat(source).contains("signalReceiveTask(processInstanceId, \"Task_Await\");");
        assertThat(source).contains("runtimeService.signal(execution.getId())");
        assertThat(source).doesNotContain("taskDefinitionKey");
    }

    // ExternalTaskService is injected only when something needs it - an app with no external task
    // should not take a bean it never uses into its constructor
    @Test
    void externalTaskServiceIsInjectedOnlyWhenAnExternalTaskExists() {
        String withExternal = readString(controllerOf(
                generator().generate(externalAndInlineServiceTaskBpmn(), List.of())));
        String withoutExternal = readString(controllerOf(generator().generate(fourActivityBpmn(), List.of())));

        assertThat(withExternal).contains("ExternalTaskService externalTaskService");
        assertThat(withoutExternal).doesNotContain("ExternalTaskService");
    }

    // one model exercising all three mechanisms at once - the endpoint layer stays uniform while
    // each activity keeps its own execution
    @Test
    void oneModelCanMixEveryTriggerKindAndStillGetOneEndpointEach() {
        GeneratedProject project = generator().generate(allTriggerKindsBpmn(), List.of());

        String source = readString(controllerOf(project));
        assertThat(countOccurrences(source, "/complete\")")).isEqualTo(3);
        assertThat(source).contains("completeUserTask(processInstanceId, \"Task_Human\");");
        assertThat(source).contains("signalReceiveTask(processInstanceId, \"Task_Await\");");
        assertThat(source).contains("completeExternalTask(processInstanceId, \"Task_Charge\");");
        // only the helpers that are actually reachable
        assertThat(source).contains("private ResponseEntity<Map<String, List<String>>> respond(");
    }

    // Repeated generation for the same BPMN model must produce deterministic output.
    @Test
    void endpointGenerationIsDeterministicForTheSameBpmn() {
        String first = readString(controllerOf(generator().generate(fourActivityBpmn(), List.of())));
        String second = readString(controllerOf(generator().generate(fourActivityBpmn(), List.of())));

        assertThat(first).isEqualTo(second);
    }

    // service tasks execute synchronously and are never open tasks, so TaskService cannot complete
    // one - an endpoint for it could only ever report "nothing to do". The generic endpoint being
    // replaced was already user-task-only for the same reason.
    @Test
    void generatesEndpointsOnlyForActivitiesThatCanActuallyBeCompleted() {
        GeneratedProject project = generator().generate(mixedTaskTypesBpmn(), List.of());

        String source = readString(controllerOf(project));
        assertThat(source).contains("@PostMapping(\"/{processInstanceId}/human-review/complete\")");
        assertThat(source).doesNotContain("charge-card");
        assertThat(countOccurrences(source, "/complete\")")).isEqualTo(1);
    }

    // two activities sharing a display name would otherwise map to the same path, which is a
    // startup failure in the generated app rather than a subtle bug
    @Test
    void duplicateActivityNamesGetDistinctEndpointPaths() {
        GeneratedProject project = generator().generate(duplicateNamesBpmn(), List.of());

        String source = readString(controllerOf(project));
        assertThat(source).contains("@PostMapping(\"/{processInstanceId}/review/complete\")");
        assertThat(source).contains("@PostMapping(\"/{processInstanceId}/review-2/complete\")");
        assertThat(source).contains("completeUserTask(processInstanceId, \"Task_First\");");
        assertThat(source).contains("completeUserTask(processInstanceId, \"Task_Second\");");
    }

    // a model with nothing completable still has to produce a controller that compiles - the
    // shared helper is only emitted when something calls it
    @Test
    void aModelWithNoCompletableActivitiesStillGeneratesAStartableController() {
        GeneratedProject project = generator().generate(loanApprovalBpmn(), List.of());

        String source = readString(controllerOf(project));
        assertThat(source).contains("@PostMapping(\"/start\")");
        assertThat(source).doesNotContain("completeUserTask");
        assertThat(source).doesNotContain("respond(");
    }

    // manufacturing controller only - the one every existing assertion in this file cares about
    // (endpoint shape, trigger dispatch, deterministic generation). The twin controller reuses the
    // exact same writeController() logic against the twin BPMN and is covered separately.
    private static Path controllerOf(GeneratedProject project) {
        return project.directory().resolve(manufacturingPackagePath(project.processKey())
                + "/controller/manufacturing/GeneratedManufacturingController.java");
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int index = haystack.indexOf(needle);
        while (index >= 0) {
            count++;
            index = haystack.indexOf(needle, index + needle.length());
        }
        return count;
    }

    // Generator rewrites DELEGATE_PACKAGE placeholder to project-specific package during file writing.
    @Test
    void writesEveryGeneratedDelegateClassIntoTheDelegatesPackage() {
        GeneratedDelegate delegate = new GeneratedDelegate("calculateInterestService", "CalculateInterestService",
                "Calculate Interest", com.metaml.workbench.codegen.DelegateKind.SERVICE_TASK,
                "package " + SpringBootProjectGenerator.DELEGATE_PACKAGE + ";\npublic class CalculateInterestService {}");

        GeneratedProject project = generator().generate(loanApprovalBpmn(), List.of(delegate));

        Path written = project.directory().resolve(manufacturingPackagePath(project.processKey())
                + "/delegate/manufacturing/CalculateInterestService.java");
        assertThat(written).exists();
        assertThat(readString(written)).contains("public class CalculateInterestService {}");
        assertThat(readString(written)).doesNotContain(SpringBootProjectGenerator.DELEGATE_PACKAGE);
    }

    // Verifies that DelegateWriteException identifies the failing BPMN element and bean
    // when delegate file generation encounters an I/O error.
    @Test
    void aDelegateThatFailsToWriteCarriesWhichBpmnElementItWasFor() throws IOException {
        GeneratedDelegate delegate = new GeneratedDelegate("brokenService", "BrokenDelegate", "Broken Task",
                com.metaml.workbench.codegen.DelegateKind.SERVICE_TASK,
                "package " + SpringBootProjectGenerator.DELEGATE_PACKAGE + "; public class BrokenDelegate {}",
                "ServiceTask_Broken");
        write(templateDir.resolve("src/main/java/com/example/camundademo/delegate/manufacturing"),
                "poison: a plain file here forces Files.createDirectories to fail for the delegate write");

        assertThatThrownBy(() -> generator().generate(loanApprovalBpmn(), List.of(delegate)))
                .isInstanceOf(DelegateWriteException.class)
                .satisfies(thrown -> {
                    DelegateWriteException e = (DelegateWriteException) thrown;
                    assertThat(e.beanName()).isEqualTo("brokenService");
                    assertThat(e.bpmnElementId()).isEqualTo("ServiceTask_Broken");
                });
    }

    @Test
    void unrelatedTemplateFilesSurviveTheCopyUntouched() {
        GeneratedProject project = generator().generate(loanApprovalBpmn(), List.of());

        Path securityConfig = project.directory().resolve(manufacturingPackagePath(project.processKey())
                + "/security/WebSecurityConfig.java");
        assertThat(securityConfig).exists();
        assertThat(readString(securityConfig)).isEqualTo("unrelated file that must survive the copy untouched");
        assertThat(project.directory().resolve("pom.xml")).exists();
    }

    @Test
    void eachGenerationGetsItsOwnProjectDirectorySoTwoModelsDontCollide() {
        GeneratedProject first = generator().generate(loanApprovalBpmn(), List.of());
        GeneratedProject second = generator().generate(loanApprovalBpmn(), List.of());

        assertThat(first.projectId()).isNotEqualTo(second.projectId());
        assertThat(first.directory()).isNotEqualTo(second.directory());
        assertThat(first.directory()).exists();
        assertThat(second.directory()).exists();
    }

    private static void write(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }

    private static String readString(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static String loanApprovalBpmn() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn2:definitions xmlns:bpmn2="http://www.omg.org/spec/BPMN/20100524/MODEL"
                    xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
                    id="Definitions_1" targetNamespace="http://bpmn.io/schema/bpmn">
                  <bpmn2:process id="loanApproval" name="Loan Approval" isExecutable="true">
                    <bpmn2:startEvent id="Start" />
                    <bpmn2:serviceTask id="ServiceTask_1" name="Calculate Interest"
                        camunda:delegateExpression="${calculateInterestService}" />
                    <bpmn2:endEvent id="End" />
                  </bpmn2:process>
                </bpmn2:definitions>
                """;
    }

    // Test fixture containing representative loan intake activities.
    private static String fourActivityBpmn() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn2:definitions xmlns:bpmn2="http://www.omg.org/spec/BPMN/20100524/MODEL"
                    id="Definitions_4" targetNamespace="http://bpmn.io/schema/bpmn">
                  <bpmn2:process id="loanIntake" name="Loan Intake" isExecutable="true">
                    <bpmn2:startEvent id="Start" />
                    <bpmn2:userTask id="Task_Review" name="Review Application" />
                    <bpmn2:userTask id="Task_CreditCheck" name="Credit Check" />
                    <bpmn2:userTask id="Task_Fraud" name="Fraud Review" />
                    <bpmn2:userTask id="Task_Approve" name="Approve Application" />
                    <bpmn2:endEvent id="End" />
                  </bpmn2:process>
                </bpmn2:definitions>
                """;
    }

    private static String externalAndInlineServiceTaskBpmn() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn2:definitions xmlns:bpmn2="http://www.omg.org/spec/BPMN/20100524/MODEL"
                    xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
                    id="Definitions_7" targetNamespace="http://bpmn.io/schema/bpmn">
                  <bpmn2:process id="externalProcess" name="External" isExecutable="true">
                    <bpmn2:startEvent id="Start" />
                    <bpmn2:serviceTask id="Task_Charge" name="Charge Card"
                        camunda:type="external" camunda:topic="charge" />
                    <bpmn2:serviceTask id="Task_Interest" name="Calculate Interest"
                        camunda:delegateExpression="${calculateInterestService}" />
                    <bpmn2:endEvent id="End" />
                  </bpmn2:process>
                </bpmn2:definitions>
                """;
    }

    private static String receiveTaskBpmn() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn2:definitions xmlns:bpmn2="http://www.omg.org/spec/BPMN/20100524/MODEL"
                    id="Definitions_8" targetNamespace="http://bpmn.io/schema/bpmn">
                  <bpmn2:process id="receiveProcess" name="Receive" isExecutable="true">
                    <bpmn2:startEvent id="Start" />
                    <bpmn2:receiveTask id="Task_Await" name="Await Settlement" />
                    <bpmn2:endEvent id="End" />
                  </bpmn2:process>
                </bpmn2:definitions>
                """;
    }

    private static String allTriggerKindsBpmn() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn2:definitions xmlns:bpmn2="http://www.omg.org/spec/BPMN/20100524/MODEL"
                    xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
                    id="Definitions_9" targetNamespace="http://bpmn.io/schema/bpmn">
                  <bpmn2:process id="allKinds" name="All Kinds" isExecutable="true">
                    <bpmn2:startEvent id="Start" />
                    <bpmn2:userTask id="Task_Human" name="Human Review" />
                    <bpmn2:receiveTask id="Task_Await" name="Await Settlement" />
                    <bpmn2:serviceTask id="Task_Charge" name="Charge Card"
                        camunda:type="external" camunda:topic="charge" />
                    <bpmn2:serviceTask id="Task_Interest" name="Calculate Interest"
                        camunda:delegateExpression="${calculateInterestService}" />
                    <bpmn2:manualTask id="Task_Manual" name="Manual Step" />
                    <bpmn2:endEvent id="End" />
                  </bpmn2:process>
                </bpmn2:definitions>
                """;
    }

    private static String mixedTaskTypesBpmn() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn2:definitions xmlns:bpmn2="http://www.omg.org/spec/BPMN/20100524/MODEL"
                    xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
                    id="Definitions_5" targetNamespace="http://bpmn.io/schema/bpmn">
                  <bpmn2:process id="mixedProcess" name="Mixed" isExecutable="true">
                    <bpmn2:startEvent id="Start" />
                    <bpmn2:userTask id="Task_Human" name="Human Review" />
                    <bpmn2:serviceTask id="Task_Charge" name="Charge Card"
                        camunda:delegateExpression="${chargeCardService}" />
                    <bpmn2:endEvent id="End" />
                  </bpmn2:process>
                </bpmn2:definitions>
                """;
    }

    private static String duplicateNamesBpmn() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn2:definitions xmlns:bpmn2="http://www.omg.org/spec/BPMN/20100524/MODEL"
                    id="Definitions_6" targetNamespace="http://bpmn.io/schema/bpmn">
                  <bpmn2:process id="dupProcess" name="Duplicates" isExecutable="true">
                    <bpmn2:startEvent id="Start" />
                    <bpmn2:userTask id="Task_First" name="Review" />
                    <bpmn2:userTask id="Task_Second" name="Review" />
                    <bpmn2:endEvent id="End" />
                  </bpmn2:process>
                </bpmn2:definitions>
                """;
    }

    private static String differentProcessKeyBpmn() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn2:definitions xmlns:bpmn2="http://www.omg.org/spec/BPMN/20100524/MODEL"
                    id="Definitions_2" targetNamespace="http://bpmn.io/schema/bpmn">
                  <bpmn2:process id="gradAdmission" name="Grad Admission" isExecutable="true">
                    <bpmn2:startEvent id="Start" />
                    <bpmn2:userTask id="Task_Review" name="Committee Review" />
                    <bpmn2:endEvent id="End" />
                  </bpmn2:process>
                </bpmn2:definitions>
                """;
    }

    // scanExisting restart persistence tests.
    @Test
    void scanExistingFindsAPreviouslyGeneratedProjectAndItsRealProcessKey() {
        GeneratedProject original = generator().generate(loanApprovalBpmn(), List.of());

        // A fresh generator instance pointed at the same outputDir simulates restart.
        List<GeneratedProject> rescanned = generator().scanExisting();

        assertThat(rescanned).hasSize(1);
        GeneratedProject found = rescanned.get(0);
        assertThat(found.projectId()).isEqualTo(original.projectId());
        assertThat(found.directory()).isEqualTo(original.directory());
        assertThat(found.processKey()).isEqualTo("loanApproval");
    }

    @Test
    void scanExistingFindsEveryGeneratedProjectNotJustTheFirst() {
        GeneratedProject first = generator().generate(loanApprovalBpmn(), List.of());
        GeneratedProject second = generator().generate(differentProcessKeyBpmn(), List.of());

        List<GeneratedProject> rescanned = generator().scanExisting();

        assertThat(rescanned).extracting(GeneratedProject::projectId)
                .containsExactlyInAnyOrder(first.projectId(), second.projectId());
        assertThat(rescanned).extracting(GeneratedProject::processKey)
                .containsExactlyInAnyOrder("loanApproval", "gradAdmission");
    }

    // scanExisting() reads the persisted directory layout independently of generator instance state.
    @Test
    void scanExistingDoesNotRequireTheSameGeneratorInstanceThatWroteTheProject() {
        generator().generate(loanApprovalBpmn(), List.of());
        SpringBootProjectGenerator differentInstance = generator();

        assertThat(differentInstance.scanExisting()).hasSize(1);
    }

    @Test
    void scanExistingReturnsEmptyWhenNothingHasEverBeenGenerated() {
        // Output directory does not exist prior to generate() invocation.
        assertThat(generator().scanExisting()).isEmpty();
    }

    // Directories lacking valid process definitions are excluded from scanExisting results.
    @Test
    void scanExistingSkipsAProjectDirectoryWithNoBpmnFile() throws IOException {
        GeneratedProject project = generator().generate(loanApprovalBpmn(), List.of());
        Path bpmnFile = project.directory().resolve("src/main/resources/processes/loanApproval.bpmn");
        Files.delete(bpmnFile);

        assertThat(generator().scanExisting()).isEmpty();
    }

    // Verifies that scanExisting resolves the active process key using project metadata
    // even when extra BPMN definitions exist in the processes directory.
    @Test
    void scanExistingResolvesAProjectWithAnExtraBpmnFileViaItsDeclaredMetadata() throws IOException {
        GeneratedProject project = generator().generate(loanApprovalBpmn(), List.of());
        write(project.directory().resolve("src/main/resources/processes/extra.bpmn"), "<bpmn>unexpected</bpmn>");

        assertThat(generator().scanExisting()).extracting(GeneratedProject::projectId)
                .containsExactly(project.projectId());
    }

    // Legacy projects lacking metadata are skipped if multiple BPMN definitions exist.
    @Test
    void scanExistingSkipsALegacyProjectDirectoryWithAmbiguousBpmnFilesAndNoMetadata() throws IOException {
        GeneratedProject project = generator().generate(loanApprovalBpmn(), List.of());
        Files.delete(project.directory().resolve(".metaml-project.properties"));
        write(project.directory().resolve("src/main/resources/processes/extra.bpmn"), "<bpmn>unexpected</bpmn>");

        assertThat(generator().scanExisting()).isEmpty();
    }

    @Test
    void scanExistingIgnoresNonDirectoryEntriesUnderOutputDir() throws IOException {
        generator().generate(loanApprovalBpmn(), List.of());
        write(outputDir.resolve("stray-file.txt"), "not a project directory");

        assertThat(generator().scanExisting()).hasSize(1);
    }

    // Project deletion tests.
    @Test
    void deleteRemovesTheWholeProjectDirectoryNotJustItsTopLevelFiles() {
        GeneratedProject project = generator().generate(loanApprovalBpmn(), List.of());
        assertThat(project.directory().resolve("src/main/resources/processes/loanApproval.bpmn")).exists();

        assertThat(generator().delete(project.projectId())).isTrue();

        assertThat(project.directory()).doesNotExist();
        assertThat(generator().scanExisting()).isEmpty();
    }

    @Test
    void deleteLeavesEveryOtherGeneratedProjectAlone() {
        GeneratedProject doomed = generator().generate(loanApprovalBpmn(), List.of());
        GeneratedProject keeper = generator().generate(differentProcessKeyBpmn(), List.of());

        assertThat(generator().delete(doomed.projectId())).isTrue();

        assertThat(doomed.directory()).doesNotExist();
        assertThat(keeper.directory()).exists();
        assertThat(generator().scanExisting()).extracting(GeneratedProject::projectId)
                .containsExactly(keeper.projectId());
    }

    // the id reaches delete() from a persisted workflow event, so a traversal-shaped one has to be
    // refused rather than resolved - this is the one operation in this class that can destroy
    // something outside the generated-projects tree if it trusts its input
    @Test
    void deleteRefusesAnIdThatWouldEscapeTheOutputDirectory() throws IOException {
        Path outsider = tempDir.resolve("not-a-generated-project");
        write(outsider.resolve("important.txt"), "must survive");

        assertThat(generator().delete("../not-a-generated-project")).isFalse();

        assertThat(outsider.resolve("important.txt")).exists();
    }

    @Test
    void deleteRefusesAnAbsolutePathDisguisedAsAProjectId() throws IOException {
        Path outsider = tempDir.resolve("elsewhere");
        write(outsider.resolve("important.txt"), "must survive");

        assertThat(generator().delete(outsider.toAbsolutePath().toString())).isFalse();

        assertThat(outsider.resolve("important.txt")).exists();
    }

    // cleanup is best-effort and runs from several lifecycle events, so the same project can
    // legitimately be offered for deletion twice - the second time is a no-op, not a failure
    @Test
    void deleteReportsFalseForAProjectThatIsAlreadyGone() {
        GeneratedProject project = generator().generate(loanApprovalBpmn(), List.of());
        assertThat(generator().delete(project.projectId())).isTrue();

        assertThat(generator().delete(project.projectId())).isFalse();
    }

    @Test
    void deleteReportsFalseForABlankOrUnknownId() {
        assertThat(generator().delete(null)).isFalse();
        assertThat(generator().delete("  ")).isFalse();
        assertThat(generator().delete("never-generated")).isFalse();
    }

    @Test
    void freshGenerationWithAllGatewaysListenersAndTasksProducesValidProjectWithPureBpmnGateways() throws IOException {
        String bpmnXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                                   xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
                                   id="Definitions_Comp" targetNamespace="http://metaml.com/test">
                  <bpmn:message id="Msg_1" name="SampleMessage" />
                  <bpmn:process id="comprehensiveProcess" isExecutable="true">
                    <bpmn:startEvent id="Start">
                      <bpmn:outgoing>Flow_1</bpmn:outgoing>
                    </bpmn:startEvent>
                    <bpmn:serviceTask id="Service_1" name="Service Step" camunda:delegateExpression="${serviceTaskBean}">
                      <bpmn:extensionElements>
                        <camunda:executionListener delegateExpression="${execListenerBean}" event="start" />
                      </bpmn:extensionElements>
                      <bpmn:incoming>Flow_1</bpmn:incoming>
                      <bpmn:outgoing>Flow_2</bpmn:outgoing>
                    </bpmn:serviceTask>
                    <bpmn:exclusiveGateway id="Gateway_Exclusive" name="Exclusive GW">
                      <bpmn:incoming>Flow_2</bpmn:incoming>
                      <bpmn:outgoing>Flow_3</bpmn:outgoing>
                    </bpmn:exclusiveGateway>
                    <bpmn:userTask id="UserTask_1" name="User Approval">
                      <bpmn:extensionElements>
                        <camunda:taskListener delegateExpression="${taskListenerBean}" event="create" />
                      </bpmn:extensionElements>
                      <bpmn:incoming>Flow_3</bpmn:incoming>
                      <bpmn:outgoing>Flow_4</bpmn:outgoing>
                    </bpmn:userTask>
                    <bpmn:parallelGateway id="Gateway_Parallel" name="Parallel GW">
                      <bpmn:incoming>Flow_4</bpmn:incoming>
                      <bpmn:outgoing>Flow_5</bpmn:outgoing>
                    </bpmn:parallelGateway>
                    <bpmn:inclusiveGateway id="Gateway_Inclusive" name="Inclusive GW">
                      <bpmn:incoming>Flow_5</bpmn:incoming>
                      <bpmn:outgoing>Flow_6</bpmn:outgoing>
                    </bpmn:inclusiveGateway>
                    <bpmn:eventBasedGateway id="Gateway_EventBased" name="Event Based GW">
                      <bpmn:incoming>Flow_6</bpmn:incoming>
                      <bpmn:outgoing>Flow_7</bpmn:outgoing>
                    </bpmn:eventBasedGateway>
                    <bpmn:intermediateCatchEvent id="Catch_Msg" name="Catch Msg">
                      <bpmn:incoming>Flow_7</bpmn:incoming>
                      <bpmn:outgoing>Flow_8</bpmn:outgoing>
                      <bpmn:messageEventDefinition messageRef="Msg_1" />
                    </bpmn:intermediateCatchEvent>
                    <bpmn:endEvent id="End">
                      <bpmn:incoming>Flow_8</bpmn:incoming>
                    </bpmn:endEvent>
                    <bpmn:sequenceFlow id="Flow_1" sourceRef="Start" targetRef="Service_1" />
                    <bpmn:sequenceFlow id="Flow_2" sourceRef="Service_1" targetRef="Gateway_Exclusive" />
                    <bpmn:sequenceFlow id="Flow_3" sourceRef="Gateway_Exclusive" targetRef="UserTask_1" />
                    <bpmn:sequenceFlow id="Flow_4" sourceRef="UserTask_1" targetRef="Gateway_Parallel" />
                    <bpmn:sequenceFlow id="Flow_5" sourceRef="Gateway_Parallel" targetRef="Gateway_Inclusive" />
                    <bpmn:sequenceFlow id="Flow_6" sourceRef="Gateway_Inclusive" targetRef="Gateway_EventBased" />
                    <bpmn:sequenceFlow id="Flow_7" sourceRef="Gateway_EventBased" targetRef="Catch_Msg" />
                    <bpmn:sequenceFlow id="Flow_8" sourceRef="Catch_Msg" targetRef="End" />
                  </bpmn:process>
                </bpmn:definitions>
                """;

        GeneratedProject project = generator().generate(bpmnXml, List.of());

        Path processesDir = project.directory().resolve("src/main/resources/processes");
        assertThat(processesDir.resolve("comprehensiveProcess.bpmn")).exists();
        assertThat(processesDir.resolve("comprehensiveProcess_twin.bpmn")).exists();

        String twinXml = readString(processesDir.resolve("comprehensiveProcess_twin.bpmn"));
        assertThat(twinXml).contains("Gateway_Exclusive");
        assertThat(twinXml).contains("Gateway_Parallel");
        assertThat(twinXml).contains("Gateway_Inclusive");
        assertThat(twinXml).contains("Gateway_EventBased");
        assertThat(twinXml).contains("Catch_Msg");

        // Confirm NO Java class files were created for any gateway
        try (var walk = Files.walk(project.directory().resolve("src/main/java"))) {
            List<String> javaFiles = walk.filter(p -> p.toString().endsWith(".java"))
                    .map(p -> p.getFileName().toString())
                    .toList();

            assertThat(javaFiles)
                    .noneMatch(name -> name.toLowerCase().contains("gateway"));
        }
    }
}
