package com.metaml.workbench.codegen;

import org.camunda.bpm.model.bpmn.Bpmn;
import org.camunda.bpm.model.bpmn.BpmnModelInstance;
import org.camunda.bpm.model.bpmn.instance.ExtensionElements;
import org.camunda.bpm.model.bpmn.instance.ServiceTask;
import org.camunda.bpm.model.bpmn.instance.UserTask;
import org.camunda.bpm.model.bpmn.instance.camunda.CamundaTaskListener;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// Generates one Java class per delegateExpression found in a saved model.
// The class name comes from the expression (${calculateInterestService} -> CalculateInterestService),
// not the task's display name: the expression is what Camunda looks up at runtime, and display names
// routinely disagree with it in both casing and wording.
// Both BPMN shapes that carry a delegateExpression are scanned - service tasks and userTask
// taskListeners - since scanning only service tasks silently produced zero delegates for models that
// use the listener form, and the generated app then crashed on the first completed task.
@Component
public class DelegateClassGenerator {

    // used when a caller doesn't care where the class ultimately lives (the standalone preview/inspection path) - a real generated project overrides this via the packageName parameter below so the class lands somewhere Spring's component scan will actually find it
    static final String DEFAULT_PACKAGE = "com.metaml.generated.delegate";

    public List<GeneratedDelegate> generate(String bpmnXml) {
        return generate(bpmnXml, DEFAULT_PACKAGE);
    }

    // One class per unique delegateExpression, not per task: two activities naming the same expression
    // already share one Spring bean, so generating twice would just be two classes fighting over one
    // @Component name.
    // Deduped by className rather than beanName, because two bean names can sanitize to the same Java
    // identifier ("bad-name" and "bad_name" both become "Bad_name") and would otherwise both be written
    // to the same file, the loser silently never existing at runtime.
    // packageName must match where the caller actually writes the file: javac tolerates a mismatch, but
    // Spring's component scan only looks under the application class's package, so the bean would never
    // register even though the build succeeds.
    public List<GeneratedDelegate> generate(String bpmnXml, String packageName) {
        BpmnModelInstance model = Bpmn.readModelFromStream(
                new ByteArrayInputStream(bpmnXml.getBytes(StandardCharsets.UTF_8)));

        // Collect every BPMN element reaching each className first, rather than building the GeneratedDelegate
        // directly - that is what lets the loop below tell "reached by exactly one element, safe to record"
        // apart from "shared, would have to guess which one".
        Map<String, List<Source>> sourcesByClassName = new LinkedHashMap<>();

        for (ServiceTask task : model.getModelElementsByType(ServiceTask.class)) {
            addSource(sourcesByClassName, task.getId(), task.getCamundaDelegateExpression(), task.getName(),
                    DelegateKind.SERVICE_TASK);
        }

        // task listeners live in a user task's <extensionElements>, not as an attribute on the task itself - camunda-bpm-model only exposes them through a typed query on that element, there is no getCamundaTaskListeners() shortcut on UserTask the way there is for a service task's own delegateExpression
        for (UserTask task : model.getModelElementsByType(UserTask.class)) {
            ExtensionElements extensionElements = task.getExtensionElements();
            if (extensionElements == null) {
                continue;
            }
            for (CamundaTaskListener listener : extensionElements.getElementsQuery()
                    .filterByType(CamundaTaskListener.class).list()) {
                addSource(sourcesByClassName, task.getId(), listener.getCamundaDelegateExpression(), task.getName(),
                        DelegateKind.TASK_LISTENER);
            }
        }

        List<GeneratedDelegate> delegates = new ArrayList<>();
        for (Map.Entry<String, List<Source>> entry : sourcesByClassName.entrySet()) {
            String className = entry.getKey();
            List<Source> sources = entry.getValue();
            // the first BPMN element encountered still names/comments the generated class, exactly as before this change (see renderSource below) - only bpmnElementId is new
            Source first = sources.get(0);
            // Two elements reaching this className under the SAME bean name is fine - that is one shared bean,
            // exactly what Camunda does at runtime. Under DIFFERENT bean names it is not: only one class can be
            // written to that path, so the other element's bean silently never exists and the process fails the
            // first time a token reaches that task. Camunda accepts both expressions at deploy time, so this is
            // the last point where the problem can still be attributed to a specific element.
            Source loser = firstWithDifferentBeanName(sources, first.beanName());
            if (loser != null) {
                throw InvalidDelegateExpressionException.collision(loser.elementId(), loser.taskName(),
                        "${" + loser.beanName() + "}", "${" + first.beanName() + "}", className);
            }
            // more than one BPMN element sharing this bean is a real, legitimate case (two activities pointing at the same service) - "go to error" pointing at an arbitrary one of them would be worse than not pointing anywhere, so this is left null rather than fabricated
            String bpmnElementId = sources.size() == 1 ? first.elementId() : null;
            String source = renderSource(packageName, className, first.beanName(), first.taskName(), first.kind());
            delegates.add(new GeneratedDelegate(first.beanName(), className, first.taskName(), first.kind(), source,
                    bpmnElementId));
        }
        return delegates;
    }

    // A ServiceTask with camunda:class is instantiated by Camunda directly via that fully-qualified class name - no Spring bean lookup involved, unlike delegateExpression. The generated stub must therefore land at the exact package the BPMN itself names, not this generator's own package convention, or the deployed process fails the first time the task runs.
    public List<GeneratedDelegate> generateFromJavaClass(String bpmnXml) {
        BpmnModelInstance model = Bpmn.readModelFromStream(
                new ByteArrayInputStream(bpmnXml.getBytes(StandardCharsets.UTF_8)));

        Map<String, String> taskNameByFqcn = new LinkedHashMap<>();
        Map<String, String> elementIdByFqcn = new LinkedHashMap<>();
        for (ServiceTask task : model.getModelElementsByType(ServiceTask.class)) {
            String fqcn = task.getCamundaClass();
            if (fqcn == null || fqcn.isBlank()) {
                continue;
            }
            taskNameByFqcn.putIfAbsent(fqcn, task.getName());
            elementIdByFqcn.putIfAbsent(fqcn, task.getId());
        }

        List<GeneratedDelegate> delegates = new ArrayList<>();
        for (Map.Entry<String, String> entry : taskNameByFqcn.entrySet()) {
            String fqcn = entry.getKey();
            int lastDot = fqcn.lastIndexOf('.');
            String packageName = lastDot > 0 ? fqcn.substring(0, lastDot) : "";
            String className = lastDot > 0 ? fqcn.substring(lastDot + 1) : fqcn;
            String source = renderJavaClassSource(packageName, className, entry.getValue());
            delegates.add(new GeneratedDelegate(className, className, entry.getValue(), DelegateKind.JAVA_CLASS,
                    source, elementIdByFqcn.get(fqcn)));
        }
        return delegates;
    }

    private static String renderJavaClassSource(String packageName, String className, String taskName) {
        String label = (taskName == null || taskName.isBlank()) ? "(unnamed activity)" : sanitizeForComment(taskName);
        String packageDecl = packageName.isBlank() ? "" : "package " + packageName + ";\n\n";
        return """
                %simport org.camunda.bpm.engine.delegate.DelegateExecution;
                import org.camunda.bpm.engine.delegate.JavaDelegate;
                import org.slf4j.Logger;
                import org.slf4j.LoggerFactory;

                // Generated from the BPMN activity "%s" (camunda:class="%s").
                public class %s implements JavaDelegate {

                    private static final Logger logger = LoggerFactory.getLogger(%s.class);

                    @Override
                    public void execute(DelegateExecution execution) {
                        logger.info("Executing generated delegate for activity \\"%s\\" (process instance {})",
                                execution.getProcessInstanceId());
                    }
                }
                """.formatted(packageDecl, label,
                packageName.isBlank() ? className : packageName + "." + className, className, className, label);
    }

    // the element that loses the collision - the first one whose bean name isn't the one the generated class is going to be named after. Returns null when every element here agrees on the bean name, i.e. the legitimate shared-delegate case.
    private static Source firstWithDifferentBeanName(List<Source> sources, String beanName) {
        for (Source source : sources) {
            if (!source.beanName().equals(beanName)) {
                return source;
            }
        }
        return null;
    }

    // The BPMN element that pointed at this delegateExpression, carried alongside the bean name, task
// name and kind.
    private record Source(String elementId, String beanName, String taskName, DelegateKind kind) {
    }

    private static void addSource(Map<String, List<Source>> sourcesByClassName, String elementId,
            String rawExpression, String taskName, DelegateKind kind) {
        // Absent and present-but-unusable are NOT the same: absent means nothing was ever claimed, skip it (Camunda rejects such a model at save anyway). Present but empty/unresolvable means the BPMN claims this task runs a delegate and names none - fail now, naming the element responsible, rather than shipping a project that fails at runtime instead.
        if (rawExpression == null) {
            return;
        }
        String beanName = unwrap(rawExpression);
        if (beanName.isBlank()) {
            throw new InvalidDelegateExpressionException(elementId, taskName, rawExpression);
        }
        String className = toClassName(beanName);
        sourcesByClassName.computeIfAbsent(className, cn -> new ArrayList<>())
                .add(new Source(elementId, beanName, taskName, kind));
    }

    // delegateExpression is stored (and read back) as the literal attribute text, "${beanName}" - this is the one place that ${...} wrapper gets stripped down to the bean name underneath it
    private static String unwrap(String delegateExpression) {
        if (delegateExpression == null) {
            return "";
        }
        String trimmed = delegateExpression.trim();
        if (trimmed.startsWith("${") && trimmed.endsWith("}")) {
            trimmed = trimmed.substring(2, trimmed.length() - 1).trim();
        }
        return trimmed;
    }

    // bean names in a delegateExpression are conventionally already valid Java identifiers (camelCase, e.g. calculateInterestService), but this is user-authored BPMN, not generated output - a stray character shouldn't produce a .java file that fails to compile
    private static String toClassName(String beanName) {
        StringBuilder sanitized = new StringBuilder(beanName.length());
        for (int i = 0; i < beanName.length(); i++) {
            char c = beanName.charAt(i);
            boolean valid = i == 0 ? Character.isJavaIdentifierStart(c) : Character.isJavaIdentifierPart(c);
            sanitized.append(valid ? c : '_');
        }
        if (sanitized.isEmpty() || !Character.isJavaIdentifierStart(sanitized.charAt(0))) {
            sanitized.insert(0, '_');
        }
        sanitized.setCharAt(0, Character.toUpperCase(sanitized.charAt(0)));
        return sanitized.toString();
    }

    // task labels are free-text from the modeler, not code - BPMN can carry an embedded newline in a name attribute (Joanna's own loanApproval.bpmn does: "Calculate\nInterest"), and every use of this label lands inside a single-line // comment, where an unsanitized newline breaks the generated file's syntax.
    private static String sanitizeForComment(String label) {
        return label.replaceAll("\\s+", " ").trim();
    }

    private static String renderSource(String packageName, String className, String beanName, String taskName,
            DelegateKind kind) {
        String label = (taskName == null || taskName.isBlank()) ? "(unnamed activity)" : sanitizeForComment(taskName);
        return kind == DelegateKind.SERVICE_TASK
                ? renderServiceTaskSource(packageName, className, beanName, label)
                : renderTaskListenerSource(packageName, className, beanName, label);
    }

    private static String renderServiceTaskSource(String packageName, String className, String beanName,
            String label) {
        return """
                package %s;

                import org.camunda.bpm.engine.delegate.DelegateExecution;
                import org.camunda.bpm.engine.delegate.JavaDelegate;
                import org.slf4j.Logger;
                import org.slf4j.LoggerFactory;
                import org.springframework.stereotype.Component;

                // Generated from the BPMN activity "%s" (camunda:delegateExpression="${%s}"). Logs on execution so the process can deploy and run end to end and its execution is directly observable - replace the log line below with real logic when ready.
                @Component("%s")
                public class %s implements JavaDelegate {

                    private static final Logger logger = LoggerFactory.getLogger(%s.class);

                    @Override
                    public void execute(DelegateExecution execution) {
                        logger.info("Executing generated delegate for activity \\"%s\\" "
                                + "(process instance {})", execution.getProcessInstanceId());
                    }
                }
                """.formatted(packageName, label, beanName, beanName, className, className, label);
    }

    // A taskListener's delegateExpression names a TaskListener, not a JavaDelegate. Generating a
    // JavaDelegate stub would compile and deploy, because Camunda only checks the interface when it
    // actually invokes the listener - surfacing as a ClassCastException the first time someone touches
    // that task rather than at generation time.
    private static String renderTaskListenerSource(String packageName, String className, String beanName,
            String label) {
        return """
                package %s;

                import org.camunda.bpm.engine.delegate.DelegateTask;
                import org.camunda.bpm.engine.delegate.TaskListener;
                import org.slf4j.Logger;
                import org.slf4j.LoggerFactory;
                import org.springframework.stereotype.Component;

                // Generated from the BPMN user task "%s" (camunda:taskListener delegateExpression="${%s}"). Logs on execution so the process can deploy and run end to end and its execution is directly observable - replace the log line below with real logic when ready.
                @Component("%s")
                public class %s implements TaskListener {

                    private static final Logger logger = LoggerFactory.getLogger(%s.class);

                    @Override
                    public void notify(DelegateTask delegateTask) {
                        logger.info("Executing generated task listener for activity \\"%s\\" "
                                + "(process instance {})", delegateTask.getProcessInstanceId());
                    }
                }
                """.formatted(packageName, label, beanName, beanName, className, className, label);
    }
}
