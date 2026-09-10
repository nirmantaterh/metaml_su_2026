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

// Generates Java delegate and listener classes for delegateExpressions declared in service tasks and task listeners.
@Component
public class DelegateClassGenerator {

    // Default package for delegate generation when no specific project package is provided.
    static final String DEFAULT_PACKAGE = "com.metaml.generated.delegate";

    public List<GeneratedDelegate> generate(String bpmnXml) {
        return generate(bpmnXml, DEFAULT_PACKAGE);
    }

    // Generates unique delegate classes deduped by class name for the target package.
    public List<GeneratedDelegate> generate(String bpmnXml, String packageName) {
        BpmnModelInstance model = Bpmn.readModelFromStream(
                new ByteArrayInputStream(bpmnXml.getBytes(StandardCharsets.UTF_8)));

        // Collects BPMN elements mapped to each class name to identify shared delegates.
        Map<String, List<Source>> sourcesByClassName = new LinkedHashMap<>();

        for (ServiceTask task : model.getModelElementsByType(ServiceTask.class)) {
            addSource(sourcesByClassName, task.getId(), task.getCamundaDelegateExpression(), task.getName(),
                    DelegateKind.SERVICE_TASK);
        }

        // Extracts task listeners from extensionElements of user tasks.
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
            // Primary BPMN element metadata used to document the generated delegate class.
            Source first = sources.get(0);
            // Validates that elements sharing a class name do not declare conflicting bean names.
            Source loser = firstWithDifferentBeanName(sources, first.beanName());
            if (loser != null) {
                throw InvalidDelegateExpressionException.collision(loser.elementId(), loser.taskName(),
                        "${" + loser.beanName() + "}", "${" + first.beanName() + "}", className);
            }
            // When multiple activities share a bean, bpmnElementId is left null to avoid ambiguous navigation.
            String bpmnElementId = sources.size() == 1 ? first.elementId() : null;
            String source = renderSource(packageName, className, first.beanName(), first.taskName(), first.kind());
            delegates.add(new GeneratedDelegate(first.beanName(), className, first.taskName(), first.kind(), source,
                    bpmnElementId));
        }
        return delegates;
    }

    // Service tasks with camunda:class require classes matching the declared FQCN rather than Spring bean delegates.
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

    private record Source(String elementId, String beanName, String taskName, DelegateKind kind) {
    }

    private static void addSource(Map<String, List<Source>> sourcesByClassName, String elementId,
            String rawExpression, String taskName, DelegateKind kind) {
        // Skips absent expressions; throws if an expression is explicitly declared but empty.
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

    // Strips ${...} wrapper from delegateExpression to isolate the bean name.
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

    // Converts bean name to a valid Java class name identifier.
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

    // Sanitizes task labels by normalizing whitespace for safe comment rendering.
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

    // Task listener delegateExpression requires TaskListener rather than JavaDelegate to avoid runtime cast failure.
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
