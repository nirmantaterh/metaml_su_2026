package com.metaml.workbench.codegen;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

class DelegateClassGeneratorTest {

    private final DelegateClassGenerator generator = new DelegateClassGenerator();

    // Joanna's own example: a service task named "Calculate Interest" wired to
    // delegateExpression="${calculateInterestService}" - the class name must come from the
    // expression, not the display name, since that's what Camunda actually looks up at runtime
    @Test
    void generatesAClassNamedAfterTheDelegateExpressionNotTheTaskLabel() {
        String bpmn = loanApprovalBpmn();

        List<GeneratedDelegate> generated = generator.generate(bpmn);

        assertThat(generated).hasSize(1);
        GeneratedDelegate delegate = generated.get(0);
        assertThat(delegate.beanName()).isEqualTo("calculateInterestService");
        assertThat(delegate.className()).isEqualTo("CalculateInterestService");
        assertThat(delegate.taskName()).isEqualTo("Calculate Interest");
        // The BPMN element id that produced this delegate, preserved through generation
        assertThat(delegate.bpmnElementId()).isEqualTo("ServiceTask_1");
    }

    @Test
    void theGeneratedSourceCompilesAsAJavaDelegateRegisteredUnderTheExpressionsBeanName() {
        List<GeneratedDelegate> generated = generator.generate(loanApprovalBpmn());
        String source = generated.get(0).sourceCode();

        assertThat(source).contains("package com.metaml.generated.delegate;");
        assertThat(source).contains("@Component(\"calculateInterestService\")");
        assertThat(source).contains("public class CalculateInterestService implements JavaDelegate");
        assertThat(source).contains("public void execute(DelegateExecution execution)");
    }

    // real bug, found by actually compiling a generated project and checking where the .class file
    // landed - javac doesn't care that a source file's package disagrees with its directory, but
    // Spring's default @ComponentScan only looks under the application's own package, so a class
    // generated with the wrong package silently never becomes a bean at runtime even though the
    // build is green. SpringBootProjectGenerator needs to be able to ask for a package that matches
    // wherever it's actually going to place the file.
    @Test
    void generateAcceptsAnExplicitPackageSoTheCallerCanMatchWhereItWillActuallyPlaceTheFile() {
        List<GeneratedDelegate> generated = generator.generate(loanApprovalBpmn(), "com.example.camundademo.delegates");

        assertThat(generated.get(0).sourceCode()).contains("package com.example.camundademo.delegates;");
    }

    @Test
    void twoActivitiesSharingOneDelegateExpressionProduceOnlyOneGeneratedClass() {
        String bpmn = twoTasksSameDelegateBpmn();

        List<GeneratedDelegate> generated = generator.generate(bpmn);

        assertThat(generated).hasSize(1);
        assertThat(generated.get(0).beanName()).isEqualTo("sharedService");
        // Task_A and Task_B both point at this bean - neither one is honestly "the"
        // source, so bpmnElementId stays null rather than picking one of them
        assertThat(generated.get(0).bpmnElementId()).isNull();
    }

    @Test
    void serviceTasksWithNoDelegateExpressionAreSkippedRatherThanGeneratingEmptyClasses() {
        String bpmn = mixedServiceTasksBpmn();

        List<GeneratedDelegate> generated = generator.generate(bpmn);

        assertThat(generated).extracting(GeneratedDelegate::beanName).containsExactly("realService");
    }

    // Camunda accepts camunda:delegateExpression="" at deploy time, so a model carrying one saves
    // cleanly and only breaks later, at runtime, with a bean that was never generated. Failing here
    // - naming the element - is what lets the editor's "Go to error" select the offending task.
    @Test
    void aDelegateExpressionThatNamesNoBeanFailsAndIdentifiesTheBpmnElement() {
        String bpmn = delegateExpressionBpmn("");

        assertThatThrownBy(() -> generator.generate(bpmn))
                .isInstanceOf(InvalidDelegateExpressionException.class)
                .hasMessageContaining("does not name a delegate bean")
                .extracting(e -> ((InvalidDelegateExpressionException) e).bpmnElementId())
                .isEqualTo("Task_A");
    }

    // Pins a real constraint found by probing camunda-bpm-model: camunda:delegateExpression=""
    // reads back as null, i.e. the parser cannot tell an empty attribute from an absent one. That
    // is why the generator skips null rather than failing on it - failing would break every model
    // whose service task legitimately has no delegate attribute at all. It also means the empty-
    // attribute shape (the only one Camunda accepts at deploy) cannot reach the element-attributed
    // failure below; see DEMO_PROTOCOL.md.
    @Test
    void anEmptyDelegateExpressionAttributeIsIndistinguishableFromAnAbsentOneAndIsSkipped() {
        String bpmn = """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn2:definitions xmlns:bpmn2="http://www.omg.org/spec/BPMN/20100524/MODEL"
                    xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
                    id="Definitions_1" targetNamespace="http://bpmn.io/schema/bpmn">
                  <bpmn2:process id="p" isExecutable="true">
                    <bpmn2:serviceTask id="Task_A" name="Odd One" camunda:delegateExpression="" />
                  </bpmn2:process>
                </bpmn2:definitions>
                """;

        assertThat(generator.generate(bpmn)).isEmpty();
    }

    // an expression that unwraps to nothing is the same defect wearing a different hat
    @Test
    void aDelegateExpressionOfOnlyWhitespaceIsTreatedAsNamingNoBean() {
        assertThatThrownBy(() -> generator.generate(delegateExpressionBpmn("   ")))
                .isInstanceOf(InvalidDelegateExpressionException.class)
                .extracting(e -> ((InvalidDelegateExpressionException) e).bpmnElementId())
                .isEqualTo("Task_A");
    }

    @Test
    void aDelegateExpressionWithIllegalJavaIdentifierCharactersGetsSanitizedNotLeftBroken() {
        String bpmn = delegateExpressionBpmn("bad-name.here");

        List<GeneratedDelegate> generated = generator.generate(bpmn);

        assertThat(generated).hasSize(1);
        // first letter uppercased, illegal chars replaced, still a real compilable identifier
        assertThat(generated.get(0).className()).isEqualTo("Bad_name_here");
    }

    // real bug, found by actually compiling generated output against the real template rather
    // than trusting these fixtures - Joanna's own loanApproval.bpmn has a literal newline
    // embedded in a task's name attribute ("Calculate\nInterest"), which broke out of the
    // single-line // comment in the generated source and turned everything after it into a
    // syntax error
    @Test
    void aTaskNameWithAnEmbeddedNewlineDoesNotBreakOutOfTheGeneratedCommentLine() {
        String bpmn = delegateExpressionBpmnWithMultilineName("calculateInterestService", "Calculate\nInterest");

        List<GeneratedDelegate> generated = generator.generate(bpmn);

        String source = generated.get(0).sourceCode();
        assertThat(source).doesNotContain("\nInterest\"");
        // every line of the file must either be blank, or start with a recognizable Java/comment
        // token - a label that broke out of its comment would leave a bare "Interest\"" line that
        // matches none of these
        for (String line : source.split("\n")) {
            String trimmed = line.trim();
            assertThat(trimmed.isEmpty()
                    || trimmed.startsWith("//")
                    || trimmed.startsWith("package")
                    || trimmed.startsWith("import")
                    || trimmed.startsWith("@")
                    || trimmed.startsWith("public")
                    || trimmed.startsWith("private")
                    || trimmed.startsWith("@Override")
                    // the generated delegate now logs its own execution (professor: "just print
                    // out a statement") - a legitimate two-line logger.info(...) call, not a
                    // broken-comment fragment
                    || trimmed.startsWith("logger.")
                    || trimmed.startsWith("+ \"")
                    || trimmed.equals("{") || trimmed.equals("}"))
                    .as("line should be recognizable Java, not a fragment of a broken comment: '%s'", line)
                    .isTrue();
        }
    }

    // This repo's own demo models (citibank wire transfer, grad admission) use exactly this shape
    // - a userTask with a camunda:taskListener, not a service task's own delegateExpression. Before
    // this test existed, generating a project from either demo model silently produced zero
    // delegates and shipped a project that crashed the first time a task completed.
    @Test
    void aUserTasksTaskListenerDelegateExpressionGeneratesATaskListenerNotAJavaDelegate() {
        String bpmn = userTaskListenerBpmn("agentExecutionDelegate", "Review Application");

        List<GeneratedDelegate> generated = generator.generate(bpmn);

        assertThat(generated).hasSize(1);
        GeneratedDelegate delegate = generated.get(0);
        assertThat(delegate.beanName()).isEqualTo("agentExecutionDelegate");
        assertThat(delegate.className()).isEqualTo("AgentExecutionDelegate");
        assertThat(delegate.kind()).isEqualTo(DelegateKind.TASK_LISTENER);
        // The userTask's own id, same chain as the serviceTask case above - a
        // taskListener's element id is the userTask that carries it, not the listener itself
        // (a <camunda:taskListener> has no id of its own in the BPMN)
        assertThat(delegate.bpmnElementId()).isEqualTo("Task_A");
        assertThat(delegate.sourceCode())
                .contains("implements TaskListener")
                .contains("public void notify(DelegateTask delegateTask)")
                .doesNotContain("JavaDelegate")
                .doesNotContain("execute(DelegateExecution");
    }

    @Test
    void aServiceTaskAndAUserTaskCanShareTheSameBpmnWithoutTheirDelegatesColliding() {
        String bpmn = """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn2:definitions xmlns:bpmn2="http://www.omg.org/spec/BPMN/20100524/MODEL"
                    xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
                    id="Definitions_1" targetNamespace="http://bpmn.io/schema/bpmn">
                  <bpmn2:process id="p" isExecutable="true">
                    <bpmn2:startEvent id="Start" />
                    <bpmn2:serviceTask id="Task_A" name="Calculate Interest"
                        camunda:delegateExpression="${calculateInterestService}" />
                    <bpmn2:userTask id="Task_B" name="Review Application">
                      <bpmn2:extensionElements>
                        <camunda:taskListener event="complete" delegateExpression="${agentExecutionDelegate}" />
                      </bpmn2:extensionElements>
                    </bpmn2:userTask>
                    <bpmn2:endEvent id="End" />
                  </bpmn2:process>
                </bpmn2:definitions>
                """;

        List<GeneratedDelegate> generated = generator.generate(bpmn);

        assertThat(generated).extracting(GeneratedDelegate::beanName)
                .containsExactlyInAnyOrder("calculateInterestService", "agentExecutionDelegate");
        assertThat(generated).extracting(GeneratedDelegate::kind)
                .containsExactlyInAnyOrder(DelegateKind.SERVICE_TASK, DelegateKind.TASK_LISTENER);
    }

    // Dedup is keyed on the generated class name, not the raw bean name: two DIFFERENT bean
    // names that sanitize down to the same Java identifier (toClassName maps every illegal
    // character to '_') both survived as separate GeneratedDelegates - which then fought over the
    // same file the moment SpringBootProjectGenerator wrote them to disk, with whichever one got
    // written second silently winning and the other bean simply never existing at runtime.
    //
    // This test used to assert only that the two didn't BOTH survive, which was the first, weaker
    // half of the fix: deduping stopped the clobbering on disk but still dropped one element's bean
    // on the floor without telling anyone, so the process still failed at runtime - just with a
    // NoSuchBeanDefinitionException instead of a race over a file. Camunda accepts both expressions
    // at deploy time, so nothing upstream catches this either. Failing here, naming the element
    // that loses, is what the editor's "Go to error" needs to select the offending task.
    @Test
    void twoDifferentBeanNamesThatSanitizeToTheSameClassNameFailAndIdentifyTheLosingElement() {
        String bpmn = """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn2:definitions xmlns:bpmn2="http://www.omg.org/spec/BPMN/20100524/MODEL"
                    xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
                    id="Definitions_1" targetNamespace="http://bpmn.io/schema/bpmn">
                  <bpmn2:process id="p" isExecutable="true">
                    <bpmn2:startEvent id="Start" />
                    <bpmn2:serviceTask id="Task_A" name="First"
                        camunda:delegateExpression="${bad-name}" />
                    <bpmn2:serviceTask id="Task_B" name="Second"
                        camunda:delegateExpression="${bad_name}" />
                    <bpmn2:endEvent id="End" />
                  </bpmn2:process>
                </bpmn2:definitions>
                """;

        // Task_A names the generated class, so Task_B is the one whose bean would never exist -
        // that's the element the error has to point at, not just "somewhere in this model"
        assertThatThrownBy(() -> generator.generate(bpmn))
                .isInstanceOf(InvalidDelegateExpressionException.class)
                .hasMessageContaining("generates the same delegate class 'Bad_name'")
                .extracting(e -> ((InvalidDelegateExpressionException) e).bpmnElementId())
                .isEqualTo("Task_B");
    }

    // the other half of the same contract: a failure that isn't attributable to one element must
    // NOT come back wearing an element id. Only InvalidDelegateExpressionException carries one
    // (see WorkbenchServiceImpl.generateErrorFrom) - anything else falls through to a StageError
    // with a null bpmnElementId, which is what keeps "Go to error" hidden rather than pointing the
    // user at a task that has nothing wrong with it.
    @Test
    void aFailureThatIsNotAboutOneElementCarriesNoElementIdRatherThanAFabricatedOne() {
        String notEvenValidBpmn = "<nonsense/>";

        assertThatThrownBy(() -> generator.generate(notEvenValidBpmn))
                .isInstanceOf(RuntimeException.class)
                .isNotInstanceOf(InvalidDelegateExpressionException.class);
    }

    private static String userTaskListenerBpmn(String expression, String taskName) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn2:definitions xmlns:bpmn2="http://www.omg.org/spec/BPMN/20100524/MODEL"
                    xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
                    id="Definitions_1" targetNamespace="http://bpmn.io/schema/bpmn">
                  <bpmn2:process id="p" isExecutable="true">
                    <bpmn2:startEvent id="Start" />
                    <bpmn2:userTask id="Task_A" name="%s">
                      <bpmn2:extensionElements>
                        <camunda:taskListener event="complete" delegateExpression="${%s}" />
                      </bpmn2:extensionElements>
                    </bpmn2:userTask>
                    <bpmn2:endEvent id="End" />
                  </bpmn2:process>
                </bpmn2:definitions>
                """.formatted(taskName, expression);
    }

    private static String delegateExpressionBpmnWithMultilineName(String expression, String taskName) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn2:definitions xmlns:bpmn2="http://www.omg.org/spec/BPMN/20100524/MODEL"
                    xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
                    id="Definitions_1" targetNamespace="http://bpmn.io/schema/bpmn">
                  <bpmn2:process id="p" isExecutable="true">
                    <bpmn2:startEvent id="Start" />
                    <bpmn2:serviceTask id="Task_A" name="%s"
                        camunda:delegateExpression="${%s}" />
                    <bpmn2:endEvent id="End" />
                  </bpmn2:process>
                </bpmn2:definitions>
                """.formatted(taskName, expression);
    }

    private static String loanApprovalBpmn() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn2:definitions xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                    xmlns:bpmn2="http://www.omg.org/spec/BPMN/20100524/MODEL"
                    xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
                    id="Definitions_1" targetNamespace="http://bpmn.io/schema/bpmn">
                  <bpmn2:process id="loanApproval" name="Loan Approval" isExecutable="true">
                    <bpmn2:startEvent id="StartEvent_1" name="Loan Request Received">
                      <bpmn2:outgoing>SequenceFlow_1</bpmn2:outgoing>
                    </bpmn2:startEvent>
                    <bpmn2:serviceTask id="ServiceTask_1" name="Calculate Interest"
                        camunda:delegateExpression="${calculateInterestService}">
                      <bpmn2:incoming>SequenceFlow_1</bpmn2:incoming>
                      <bpmn2:outgoing>SequenceFlow_2</bpmn2:outgoing>
                    </bpmn2:serviceTask>
                    <bpmn2:sequenceFlow id="SequenceFlow_1" sourceRef="StartEvent_1" targetRef="ServiceTask_1" />
                    <bpmn2:endEvent id="EndEvent_1" name="Loan Approved">
                      <bpmn2:incoming>SequenceFlow_2</bpmn2:incoming>
                    </bpmn2:endEvent>
                    <bpmn2:sequenceFlow id="SequenceFlow_2" sourceRef="ServiceTask_1" targetRef="EndEvent_1" />
                  </bpmn2:process>
                </bpmn2:definitions>
                """;
    }

    private static String twoTasksSameDelegateBpmn() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn2:definitions xmlns:bpmn2="http://www.omg.org/spec/BPMN/20100524/MODEL"
                    xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
                    id="Definitions_1" targetNamespace="http://bpmn.io/schema/bpmn">
                  <bpmn2:process id="p" isExecutable="true">
                    <bpmn2:startEvent id="Start" />
                    <bpmn2:serviceTask id="Task_A" name="First Call"
                        camunda:delegateExpression="${sharedService}" />
                    <bpmn2:serviceTask id="Task_B" name="Second Call"
                        camunda:delegateExpression="${sharedService}" />
                    <bpmn2:endEvent id="End" />
                  </bpmn2:process>
                </bpmn2:definitions>
                """;
    }

    private static String mixedServiceTasksBpmn() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn2:definitions xmlns:bpmn2="http://www.omg.org/spec/BPMN/20100524/MODEL"
                    xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
                    id="Definitions_1" targetNamespace="http://bpmn.io/schema/bpmn">
                  <bpmn2:process id="p" isExecutable="true">
                    <bpmn2:startEvent id="Start" />
                    <bpmn2:serviceTask id="Task_A" name="No Delegate Yet" />
                    <bpmn2:serviceTask id="Task_B" name="Real One"
                        camunda:delegateExpression="${realService}" />
                    <bpmn2:endEvent id="End" />
                  </bpmn2:process>
                </bpmn2:definitions>
                """;
    }

    @Test
    void generateFromJavaClassProducesADelegateAtTheBpmnsOwnPackage() {
        String bpmn = javaClassBpmn("com.redcollar.manuf.api.delegates.VerifyOrderDetailsDelegate",
                "Verify Order Details");

        List<GeneratedDelegate> generated = generator.generateFromJavaClass(bpmn);

        assertThat(generated).hasSize(1);
        GeneratedDelegate delegate = generated.get(0);
        assertThat(delegate.className()).isEqualTo("VerifyOrderDetailsDelegate");
        assertThat(delegate.kind()).isEqualTo(DelegateKind.JAVA_CLASS);
        assertThat(delegate.sourceCode())
                .contains("package com.redcollar.manuf.api.delegates;")
                .contains("public class VerifyOrderDetailsDelegate implements JavaDelegate")
                .doesNotContain("@Component");
    }

    @Test
    void generateFromJavaClassIsGenericToAnyPackageNotJustRedCollar() {
        String bpmn = javaClassBpmn("com.example.orders.api.delegates.UpdateInventoryDelegate",
                "Update Inventory");

        List<GeneratedDelegate> generated = generator.generateFromJavaClass(bpmn);

        assertThat(generated.get(0).sourceCode()).contains("package com.example.orders.api.delegates;")
                .contains("public class UpdateInventoryDelegate implements JavaDelegate");
    }

    @Test
    void generateFromJavaClassIgnoresExternalAndDelegateExpressionTasks() {
        List<GeneratedDelegate> generated = generator.generateFromJavaClass(loanApprovalBpmn());
        assertThat(generated).isEmpty();
    }

    private static String javaClassBpmn(String fqcn, String taskName) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn2:definitions xmlns:bpmn2="http://www.omg.org/spec/BPMN/20100524/MODEL"
                    xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
                    id="Definitions_1" targetNamespace="http://bpmn.io/schema/bpmn">
                  <bpmn2:process id="p" isExecutable="true">
                    <bpmn2:startEvent id="Start" />
                    <bpmn2:serviceTask id="Task_A" name="%s" camunda:class="%s" />
                    <bpmn2:endEvent id="End" />
                  </bpmn2:process>
                </bpmn2:definitions>
                """.formatted(taskName, fqcn);
    }

    private static String delegateExpressionBpmn(String rawExpression) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn2:definitions xmlns:bpmn2="http://www.omg.org/spec/BPMN/20100524/MODEL"
                    xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
                    id="Definitions_1" targetNamespace="http://bpmn.io/schema/bpmn">
                  <bpmn2:process id="p" isExecutable="true">
                    <bpmn2:startEvent id="Start" />
                    <bpmn2:serviceTask id="Task_A" name="Odd One"
                        camunda:delegateExpression="${%s}" />
                    <bpmn2:endEvent id="End" />
                  </bpmn2:process>
                </bpmn2:definitions>
                """.formatted(rawExpression);
    }
}
