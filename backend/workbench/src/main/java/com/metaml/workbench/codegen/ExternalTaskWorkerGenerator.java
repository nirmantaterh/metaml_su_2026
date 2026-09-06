package com.metaml.workbench.codegen;

import org.camunda.bpm.model.bpmn.Bpmn;
import org.camunda.bpm.model.bpmn.BpmnModelInstance;
import org.camunda.bpm.model.bpmn.instance.Activity;
import org.camunda.bpm.model.bpmn.instance.ExclusiveGateway;
import org.camunda.bpm.model.bpmn.instance.FlowNode;
import org.camunda.bpm.model.bpmn.instance.SequenceFlow;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Generates one external-task worker per camunda:topic - the counterpart to DelegateClassGenerator for
// delegateExpression tasks. A serviceTask with camunda:type="external" is a wait state the engine
// never runs on its own, so without a worker on its topic the token parks there forever.
// Uses the embedded engine's ExternalTaskService directly (fetchAndLock + complete) rather than the
// external-task client starter, which pulls in the Camunda REST starter and its Jersey dependency.
// simulateMlAgent decides whether the worker delegates its completion variables to a TwinDecisionAgent
// bean instead of just logging and completing - that is what makes the twin side pluggable. Proxy
// workers only log and complete; they are driven by the real business systems the process targets.
// Gateway variables: when an external task immediately precedes an exclusive gateway whose condition
// reads a process variable, the worker must set it or the gateway throws PropertyNotFoundException.
// Generated workers fail explicitly when no legitimate producer exists - replace the generated throw
// with real business logic in the target project.
@Component
public class ExternalTaskWorkerGenerator {

    private static final String CAMUNDA_NS = "http://camunda.org/schema/1.0/bpmn";
    private static final String EXTERNAL_IMPLEMENTATION = "external";
    // Simple variable references: ${varName} or ${!varName}
    private static final Pattern CONDITION_VAR_PATTERN = Pattern.compile("\\$\\{!?(\\w+)\\}");
    // Complex expressions: ${execution.getVariable('varName') == value}
    // Extracts the variable name from getVariable('...') or getVariable("...") calls.
    // Deliberately narrow: only the exact forms that can be safely and deterministically
    // understood, not a general EL parser.
    private static final Pattern GETTER_VAR_PATTERN =
            Pattern.compile("execution\\.getVariable\\(['\"]([^'\"]+)['\"]\\)");
    // EL reserved words that the regex may capture from literal expressions like ${false}.
    // These are not process variables and must not be treated as such.
    private static final Set<String> EL_RESERVED = Set.of(
            "true", "false", "null", "empty",
            "not", "and", "or", "eq", "ne", "lt", "gt", "le", "ge",
            "instanceof", "div", "mod");

    // One worker per unique topic, in document order. Two tasks sharing a topic share the one subscription at runtime, so generating two classes for it would just be two beans fighting over the same topic - deduped by topic for the same reason DelegateClassGenerator dedups by className.
    public List<GeneratedWorker> generate(String bpmnXml, String packageName, boolean simulateMlAgent) {
        BpmnModelInstance model = Bpmn.readModelFromStream(
                new ByteArrayInputStream(bpmnXml.getBytes(StandardCharsets.UTF_8)));

        Map<String, Set<String>> gatewayVarsByTopic = detectGatewayVariables(model);

        Set<String> usedClassNames = new LinkedHashSet<>();
        Map<String, GeneratedWorker> byTopic = new LinkedHashMap<>();
        for (Activity element : model.getModelElementsByType(Activity.class)) {
            if (!EXTERNAL_IMPLEMENTATION.equals(element.getAttributeValueNs(CAMUNDA_NS, "type"))) {
                continue;
            }
            String topic = element.getAttributeValueNs(CAMUNDA_NS, "topic");
            if (topic == null || topic.isBlank() || byTopic.containsKey(topic)) {
                continue;
            }
            String className = disambiguateClassName(toClassName(topic), usedClassNames);
            String label = element.getName() == null || element.getName().isBlank()
                    ? topic
                    : sanitizeForComment(element.getName());
            Set<String> gatewayVars = gatewayVarsByTopic.getOrDefault(topic, Set.of());
            byTopic.put(topic, new GeneratedWorker(className, topic,
                    renderSource(packageName, className, topic, label, simulateMlAgent, gatewayVars)));
        }
        return new ArrayList<>(byTopic.values());
    }

    // Finds process variables referenced in exclusive-gateway conditions and maps them back to the external-task topic whose worker must set them. Only direct predecessors are traced: if the incoming flow's source is an external-task activity, its topic gets the variable. Intermediate elements (catches, other tasks) are not followed — gateway variables in those cases need different handling anyway.
    public static Map<String, Set<String>> detectGatewayVariables(BpmnModelInstance model) {
        Map<String, Set<String>> result = new LinkedHashMap<>();
        for (ExclusiveGateway gw : model.getModelElementsByType(ExclusiveGateway.class)) {
            Set<String> varNames = extractConditionVariables(gw);
            if (varNames.isEmpty()) {
                continue;
            }
            for (SequenceFlow incoming : gw.getIncoming()) {
                FlowNode source = incoming.getSource();
                if (source instanceof Activity activity
                        && EXTERNAL_IMPLEMENTATION.equals(activity.getAttributeValueNs(CAMUNDA_NS, "type"))) {
                    String topic = activity.getAttributeValueNs(CAMUNDA_NS, "topic");
                    if (topic != null && !topic.isBlank()) {
                        result.computeIfAbsent(topic, k -> new LinkedHashSet<>()).addAll(varNames);
                    }
                }
            }
        }
        return result;
    }

    // Like detectGatewayVariables but keyed by BPMN activity element ID rather than external-task
    // topic, and covering ANY activity type. Used by the Workbench simulation to determine which
    // gateway variables an activity feeds regardless of whether it is an external task, user task,
    // or service task. Lets the Workbench set explicit simulation values on a process instance
    // before the gateway evaluates, instead of relying on an EL-resolver fallback.
    public static Map<String, Set<String>> detectGatewayVariablesByActivityId(BpmnModelInstance model) {
        Map<String, Set<String>> result = new LinkedHashMap<>();
        for (ExclusiveGateway gw : model.getModelElementsByType(ExclusiveGateway.class)) {
            Set<String> varNames = extractConditionVariables(gw);
            if (varNames.isEmpty()) {
                continue;
            }
            for (SequenceFlow incoming : gw.getIncoming()) {
                FlowNode source = incoming.getSource();
                if (source instanceof Activity) {
                    result.computeIfAbsent(source.getId(), k -> new LinkedHashSet<>()).addAll(varNames);
                }
            }
        }
        return result;
    }

    private static Set<String> extractConditionVariables(ExclusiveGateway gw) {
        Set<String> varNames = new LinkedHashSet<>();
        for (SequenceFlow outgoing : gw.getOutgoing()) {
            if (outgoing.getConditionExpression() != null) {
                String expr = outgoing.getConditionExpression().getTextContent();
                if (expr != null) {
                    // Simple variable references: ${varName}, ${!varName}
                    Matcher m = CONDITION_VAR_PATTERN.matcher(expr);
                    while (m.find()) {
                        String name = m.group(1);
                        if (!EL_RESERVED.contains(name)) {
                            varNames.add(name);
                        }
                    }
                    // Complex expressions: execution.getVariable('varName')
                    Matcher g = GETTER_VAR_PATTERN.matcher(expr);
                    while (g.find()) {
                        varNames.add(g.group(1));
                    }
                }
            }
        }
        return varNames;
    }

    // topics are conventionally already valid Java identifiers (e.g. SamplingTwin), but this is user-authored BPMN - a stray character shouldn't produce a .java file that fails to compile.
    private static String toClassName(String topic) {
        StringBuilder sanitized = new StringBuilder(topic.length());
        for (int i = 0; i < topic.length(); i++) {
            char c = topic.charAt(i);
            boolean valid = i == 0 ? Character.isJavaIdentifierStart(c) : Character.isJavaIdentifierPart(c);
            sanitized.append(valid ? c : '_');
        }
        if (sanitized.isEmpty() || !Character.isJavaIdentifierStart(sanitized.charAt(0))) {
            sanitized.insert(0, '_');
        }
        sanitized.setCharAt(0, Character.toUpperCase(sanitized.charAt(0)));
        return sanitized.append("Worker").toString();
    }

    // Resolves collisions where distinct external-task topics sanitize to the same base Java class name
    // (e.g. "Foo-Bar" and "Foo_Bar" both sanitize to "Foo_BarWorker"). Disambiguates by appending a numeric
    // suffix in document order, preserving stability for non-colliding names while preventing overwrite.
    private static String disambiguateClassName(String baseClassName, Set<String> usedClassNames) {
        if (usedClassNames.add(baseClassName)) {
            return baseClassName;
        }
        String prefix = baseClassName.endsWith("Worker")
                ? baseClassName.substring(0, baseClassName.length() - "Worker".length())
                : baseClassName;
        int suffix = 2;
        while (true) {
            String candidate = prefix + "_" + suffix + "Worker";
            if (usedClassNames.add(candidate)) {
                return candidate;
            }
            suffix++;
        }
    }

    private static String sanitizeForComment(String label) {
        return label.replaceAll("\\s+", " ").trim();
    }

    private static String renderSource(String packageName, String className, String topic, String label,
            boolean simulateMlAgent, Set<String> gatewayVars) {
        return simulateMlAgent
                ? renderTwinWorkerSource(packageName, className, topic, label, gatewayVars)
                : renderPlainWorkerSource(packageName, className, topic, label, gatewayVars);
    }

    // Twin workers delegate their completion variables to an optionally injected TwinDecisionAgent - the
    // boundary a real model or agent attaches to.
    // ObjectProvider rather than a direct injection or a @ConditionalOnMissingBean fallback bean: that
    // annotation is only honoured reliably inside @Configuration classes, so as a component-scanned bean
    // the fallback never registers and the app fails to start. getIfAvailable() returns null cleanly with
    // nothing wired in, and the real implementation the moment one exists.
    // Gateway variables this topic feeds must also land in the completion map, so the worker fills in
    // anything still missing after either path runs rather than requiring every TwinDecisionAgent to know
    // which topics feed which gateways. A real agent that does set the variable simply wins.
    private static String renderTwinWorkerSource(String packageName, String className, String topic, String label,
            Set<String> gatewayVars) {
        // Derive the worker base package for the GeneratedExternalTaskWorker import
        String workerBasePackage = packageName.substring(0, packageName.lastIndexOf('.'));
        // Gateway variable fallback throws IllegalStateException instead of
        // Math.random() or Boolean.TRUE. A real TwinDecisionAgent implementation must
        // provide legitimate business logic. The absence of a required variable is a
        // missing-state condition — fail explicitly rather than fabricate a decision.
        StringBuilder fallbackLines = new StringBuilder();
        for (String varName : gatewayVars) {
            fallbackLines.append("            if (!variables.containsKey(\"").append(varName).append("\")) {\n")
                    .append("                // TODO: replace with real business logic from TwinDecisionAgent\n")
                    .append("                throw new IllegalStateException(\"Gateway variable '")
                    .append(varName).append("' was not set by TwinDecisionAgent — register a @Component ")
                    .append("implementing TwinDecisionAgent to provide real business decisions\");\n            }\n");
        }
        return """
                package %1$s;

                import java.util.HashMap;
                import java.util.Map;
                import java.util.UUID;

                import org.camunda.bpm.engine.ExternalTaskService;
                import org.camunda.bpm.engine.externaltask.LockedExternalTask;
                import org.slf4j.Logger;
                import org.slf4j.LoggerFactory;
                import org.springframework.beans.factory.ObjectProvider;
                import org.springframework.stereotype.Component;

                import %5$s.GeneratedExternalTaskWorker;

                // Generated for external-task topic "%2$s" (BPMN activity "%3$s").
                @Component
                public class %4$s implements GeneratedExternalTaskWorker {

                    private static final Logger logger = LoggerFactory.getLogger(%4$s.class);

                    private final ObjectProvider<TwinDecisionAgent> agentProvider;

                    public %4$s(ObjectProvider<TwinDecisionAgent> agentProvider) {
                        this.agentProvider = agentProvider;
                    }

                    @Override
                    public String topic() {
                        return "%2$s";
                    }

                    @Override
                    public void execute(LockedExternalTask task, ExternalTaskService externalTaskService) {
                        TwinDecisionAgent agent = agentProvider.getIfAvailable();
                        Map<String, Object> variables;
                        if (agent != null) {
                            logger.info("[Twin] Invoking decision agent {} for activity \\"%3$s\\" "
                                    + "(process instance {})", agent.getClass().getSimpleName(), task.getProcessInstanceId());
                            variables = new HashMap<>(agent.decide("%2$s", task));
                        } else {
                            // No TwinDecisionAgent registered - built-in fallback. Produces synthetic, runtime-varying output rather than a predetermined business outcome so the twin can still run standalone; register a @Component implementing TwinDecisionAgent to replace this with a real model/agent call.
                            logger.info("[Twin] Invoking simulated ML agent for activity \\"%3$s\\" "
                                    + "(process instance {})", task.getProcessInstanceId());
                            variables = new HashMap<>();
                            variables.put("agentTopic", "%2$s");
                            variables.put("agentInvocationId", UUID.randomUUID().toString());
                            variables.put("agentTimestamp", System.currentTimeMillis());
                            logger.info("[Twin] Agent invocation result: {}", variables);
                        }
                %6$s        logger.info("[Twin] Completion variables: {}", variables);
                        externalTaskService.complete(task.getId(), "generated-worker", variables);
                    }
                }
                """.formatted(packageName, topic, label, className, workerBasePackage, fallbackLines);
    }

    private static String renderPlainWorkerSource(String packageName, String className, String topic, String label,
            Set<String> gatewayVars) {
        String workerBasePackage = packageName.substring(0, packageName.lastIndexOf('.'));
        if (gatewayVars.isEmpty()) {
            return """
                    package %1$s;

                    import org.camunda.bpm.engine.ExternalTaskService;
                    import org.camunda.bpm.engine.externaltask.LockedExternalTask;
                    import org.slf4j.Logger;
                    import org.slf4j.LoggerFactory;
                    import org.springframework.stereotype.Component;

                    import %5$s.GeneratedExternalTaskWorker;

                    // Generated for external-task topic "%2$s" (BPMN activity "%3$s").
                    @Component
                    public class %4$s implements GeneratedExternalTaskWorker {

                        private static final Logger logger = LoggerFactory.getLogger(%4$s.class);

                        @Override
                        public String topic() {
                            return "%2$s";
                        }

                        @Override
                        public void execute(LockedExternalTask task, ExternalTaskService externalTaskService) {
                            logger.info("Executing generated external-task worker for activity \\"%3$s\\" "
                                    + "(process instance {})", task.getProcessInstanceId());
                            externalTaskService.complete(task.getId(), "generated-worker");
                        }
                    }
                    """.formatted(packageName, topic, label, className, workerBasePackage);
        }

        // Generated workers that precede gateways must fail explicitly when the
        // gateway variable has no legitimate producer. The generated code throws rather than
        // fabricating a random business decision. Replace the throw with real business logic
        // (a service call, a rule engine, an ML model inference, etc.) in the generated project.
        StringBuilder varLines = new StringBuilder();
        for (String varName : gatewayVars) {
            varLines.append("            if (!variables.containsKey(\"").append(varName).append("\")) {\n")
                    .append("                // TODO: replace with real business logic\n")
                    .append("                throw new IllegalStateException(\"Gateway variable '")
                    .append(varName).append("' must be set by a legitimate producer — ")
                    .append("implement business logic in this worker\");\n            }\n");
        }

        String body = "            logger.info(\"Executing generated external-task worker for activity \\\""
                + sanitizeForJavaString(label)
                + "\\\" \"\n                    + \"(process instance {})\", task.getProcessInstanceId());\n"
                + "            Map<String, Object> variables = new HashMap<>();\n"
                + varLines
                + "            logger.info(\"Worker completion variables: {}\", variables);\n"
                + "            externalTaskService.complete(task.getId(), \"generated-worker\", variables);";

        return """
                package %1$s;

                import java.util.HashMap;
                import java.util.Map;

                import org.camunda.bpm.engine.ExternalTaskService;
                import org.camunda.bpm.engine.externaltask.LockedExternalTask;
                import org.slf4j.Logger;
                import org.slf4j.LoggerFactory;
                import org.springframework.stereotype.Component;

                import %6$s.GeneratedExternalTaskWorker;

                // Generated for external-task topic "%2$s" (BPMN activity "%3$s").
                @Component
                public class %4$s implements GeneratedExternalTaskWorker {

                    private static final Logger logger = LoggerFactory.getLogger(%4$s.class);

                    @Override
                    public String topic() {
                        return "%2$s";
                    }

                    @Override
                    public void execute(LockedExternalTask task, ExternalTaskService externalTaskService) {
                %5$s
                    }
                }
                """.formatted(packageName, topic, label, className, body, workerBasePackage);
    }

    private static String sanitizeForJavaString(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
