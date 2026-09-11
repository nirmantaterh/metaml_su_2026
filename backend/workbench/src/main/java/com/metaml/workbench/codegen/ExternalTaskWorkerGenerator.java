package com.metaml.workbench.codegen;

import org.camunda.bpm.model.bpmn.Bpmn;
import org.camunda.bpm.model.bpmn.BpmnModelInstance;
import org.camunda.bpm.model.bpmn.instance.Activity;
import org.camunda.bpm.model.bpmn.instance.ExclusiveGateway;
import org.camunda.bpm.model.bpmn.instance.FlowNode;
import org.camunda.bpm.model.bpmn.instance.SequenceFlow;
import org.springframework.stereotype.Component;

import com.metaml.workbench.bpmn.BpmnCapabilityContractReader;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Generates Spring-managed workers for BPMN external tasks via {@code ExternalTaskService}.
 */
@Component
public class ExternalTaskWorkerGenerator {

    private static final String CAMUNDA_NS = "http://camunda.org/schema/1.0/bpmn";
    private static final String EXTERNAL_IMPLEMENTATION = "external";
    // Never collides with a topic-derived class name: toClassName() always appends "Worker".
    private static final String GATEWAY_OUTPUT_PROVIDER_CLASS_NAME = "GatewayOutputProvider";

    // Generates workers for external tasks, deduplicated by topic.
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
            String activityId = element.getId();
            String className = disambiguateClassName(toClassName(topic), usedClassNames);
            String label = element.getName() == null || element.getName().isBlank()
                    ? topic
                    : sanitizeForComment(element.getName());
            Set<String> gatewayVars = gatewayVarsByTopic.getOrDefault(topic, Set.of());
            byTopic.put(topic, new GeneratedWorker(className, topic, activityId,
                    renderSource(packageName, className, topic, activityId, label, simulateMlAgent, gatewayVars)));
        }

        List<GeneratedWorker> workers = new ArrayList<>(byTopic.values());
        // Plain (non-Twin) workers whose topic precedes an exclusive gateway need the same
        // pluggable legitimate-producer boundary Twin workers already get via TwinDecisionAgent
        // (see renderTwinWorkerSource): without it there is no way for a real business decision to
        // ever reach the `variables` map before the guard in renderPlainWorkerSource throws, so the
        // worker fails unconditionally instead of only when no legitimate producer is registered.
        // Emitted once per generation call as an ordinary GeneratedWorker entry (not tied to any
        // BPMN topic) so it flows through the caller's existing, generic worker-file-writing path
        // unchanged - SpringBootProjectGenerator.java is protected and must not be modified.
        boolean needsGatewayOutputProvider = !simulateMlAgent
                && gatewayVarsByTopic.keySet().stream().anyMatch(byTopic::containsKey);
        if (needsGatewayOutputProvider) {
            workers.add(new GeneratedWorker(GATEWAY_OUTPUT_PROVIDER_CLASS_NAME, "", "",
                    renderGatewayOutputProviderInterface(packageName)));
        }
        return workers;
    }

    // Maps gateway condition variable references back to predecessor external-task topics.
    public static Map<String, Set<String>> detectGatewayVariables(BpmnModelInstance model) {
        Map<String, Set<String>> result = new LinkedHashMap<>();
        for (ExclusiveGateway gw : model.getModelElementsByType(ExclusiveGateway.class)) {
            Set<String> varNames = BpmnCapabilityContractReader.gatewayConditionVariables(gw);
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

    // Maps gateway condition variables by predecessor BPMN activity element ID.
    //
    // The implementation lives in BpmnCapabilityContractReader, which is the demand-side authority on
    // what an activity is required to produce (requiredOutputs = these gateway variables union
    // metaml:agentOutputs) and which a generated Target Platform can reach without depending on this
    // codegen module. This delegating form is kept so every existing caller and test is unaffected.
    public static Map<String, Set<String>> detectGatewayVariablesByActivityId(BpmnModelInstance model) {
        return BpmnCapabilityContractReader.gatewayConditionVariablesByActivityId(model);
    }


    // Sanitizes the topic name into a valid Java worker class identifier.
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

    // Disambiguates colliding class names by appending an incremental numeric suffix.
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

    private static String renderSource(String packageName, String className, String topic, String activityId,
            String label, boolean simulateMlAgent, Set<String> gatewayVars) {
        return simulateMlAgent
                ? renderTwinWorkerSource(packageName, className, topic, activityId, label, gatewayVars)
                : renderPlainWorkerSource(packageName, className, topic, activityId, label, gatewayVars);
    }

    // Twin workers attempt the generic P7 Step 5 capability runtime first - CapabilityDispatcher,
    // resolving either this process instance's own evolvedAgent_*/evolvedAgentType_* variables or a
    // cached Workbench-authoritative CapabilityBinding - and fall through to the pre-existing optional
    // TwinDecisionAgent ONLY when neither resolves anything. A capability-bound activity therefore
    // never touches TwinDecisionAgent at all; TwinDecisionAgent's own existing role - the twin-side
    // decision boundary for activities with no capability binding - is otherwise unchanged.
    //
    // REQUIRES_CAPABILITY_OUTPUT reuses the exact same demand signal (gatewayVars, derived from this
    // topic preceding an exclusive gateway) the pre-existing TwinDecisionAgent guard below already
    // uses: when true and dispatch() found nothing, this activity fails explicitly
    // (CapabilityBindingRequiredException) rather than silently falling through to a fabricated
    // TwinDecisionAgent/synthetic result. When false, dispatch() finding nothing is genuinely harmless
    // and TwinDecisionAgent (or its synthetic fallback) runs exactly as it always has.
    private static String renderTwinWorkerSource(String packageName, String className, String topic,
            String activityId, String label, Set<String> gatewayVars) {
        String workerBasePackage = packageName.substring(0, packageName.lastIndexOf('.'));
        String safeActivityId = sanitizeForJavaString(activityId == null || activityId.isBlank() ? topic
                : activityId);
        boolean requiresCapabilityOutput = !gatewayVars.isEmpty();
        // Verify required gateway variables are populated.
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
                import java.util.Optional;
                import java.util.UUID;

                import org.camunda.bpm.engine.ExternalTaskService;
                import org.camunda.bpm.engine.RuntimeService;
                import org.camunda.bpm.engine.externaltask.LockedExternalTask;
                import org.slf4j.Logger;
                import org.slf4j.LoggerFactory;
                import org.springframework.beans.factory.ObjectProvider;
                import org.springframework.stereotype.Component;

                import com.metaml.workbench.automation.AutomationResult;
                import com.metaml.workbench.capability.runtime.CapabilityBindingRequiredException;
                import com.metaml.workbench.capability.runtime.CapabilityDispatcher;
                import com.metaml.workbench.capability.runtime.ExternalTaskExecutionContext;

                import %5$s.GeneratedExternalTaskWorker;

                // Generated for external-task topic "%2$s" (BPMN activity "%3$s").
                @Component
                public class %4$s implements GeneratedExternalTaskWorker {

                    private static final Logger logger = LoggerFactory.getLogger(%4$s.class);
                    private static final String ACTIVITY_ID = "%7$s";
                    private static final boolean REQUIRES_CAPABILITY_OUTPUT = %8$s;

                    private final ObjectProvider<TwinDecisionAgent> agentProvider;
                    private final ObjectProvider<CapabilityDispatcher> capabilityDispatcherProvider;
                    private final RuntimeService runtimeService;

                    public %4$s(ObjectProvider<TwinDecisionAgent> agentProvider,
                            ObjectProvider<CapabilityDispatcher> capabilityDispatcherProvider,
                            RuntimeService runtimeService) {
                        this.agentProvider = agentProvider;
                        this.capabilityDispatcherProvider = capabilityDispatcherProvider;
                        this.runtimeService = runtimeService;
                    }

                    @Override
                    public String topic() {
                        return "%2$s";
                    }

                    @Override
                    public void execute(LockedExternalTask task, ExternalTaskService externalTaskService) {
                        CapabilityDispatcher dispatcher = capabilityDispatcherProvider.getIfAvailable();
                        if (dispatcher != null) {
                            ExternalTaskExecutionContext context =
                                    new ExternalTaskExecutionContext(task, runtimeService);
                            Optional<AutomationResult> dispatched = dispatcher.dispatch(context, ACTIVITY_ID, null);
                            if (dispatched.isPresent()) {
                                logger.info("[Twin] Capability-dispatched activity \\"%3$s\\" "
                                        + "(process instance {})", task.getProcessInstanceId());
                                externalTaskService.complete(task.getId(), "generated-worker",
                                        context.completionVariables());
                                return;
                            }
                            if (REQUIRES_CAPABILITY_OUTPUT) {
                                throw new CapabilityBindingRequiredException(ACTIVITY_ID);
                            }
                        }
                        TwinDecisionAgent agent = agentProvider.getIfAvailable();
                        Map<String, Object> variables;
                        if (agent != null) {
                            logger.info("[Twin] Invoking decision agent {} for activity \\"%3$s\\" "
                                    + "(process instance {})", agent.getClass().getSimpleName(), task.getProcessInstanceId());
                            variables = new HashMap<>(agent.decide("%2$s", task));
                        } else {
                            // No TwinDecisionAgent registered - built-in fallback.
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
                """.formatted(packageName, topic, label, className, workerBasePackage, fallbackLines,
                        safeActivityId, requiresCapabilityOutput);
    }

    // The pluggable legitimate-producer boundary every gateway-guarded plain worker calls instead
    // of throwing unconditionally. Mirrors TwinDecisionAgent's shape and role on the Twin side: with
    // no implementation registered, a worker still has no legitimate way to produce the gateway's
    // required output and fails explicitly (see renderPlainWorkerSource's guard) rather than
    // fabricating one - Math.random(), Boolean.TRUE, or any other invented value.
    private static String renderGatewayOutputProviderInterface(String packageName) {
        return """
                package %1$s;

                import java.util.Map;

                import org.camunda.bpm.engine.externaltask.LockedExternalTask;

                // Pluggable legitimate-producer boundary for every generated worker whose topic precedes
                // an exclusive gateway. With no implementation registered, such a worker has no legitimate
                // way to produce the gateway's required output and fails explicitly rather than fabricating
                // one. Register your own @Component implementing this interface with the real business
                // logic that determines the outcome (e.g. an order-approval service, a quality-check
                // integration); every generated worker for a gateway-guarded topic starts calling it
                // instead, with no generated code to change.
                public interface GatewayOutputProvider {

                    // topic: the external-task topic being completed (e.g. "VerifyOrder") - lets one
                    // implementation branch on which BPMN activity it is deciding for. task: the locked
                    // external task itself, for id/businessKey/variable access. Returns the process
                    // variables to complete the task with; include every gateway variable this topic's
                    // activity precedes if you can - the calling worker fails explicitly for whichever
                    // ones are still missing rather than completing with a fabricated one.
                    Map<String, Object> provide(String topic, LockedExternalTask task);
                }
                """.formatted(packageName);
    }

    private static String renderPlainWorkerSource(String packageName, String className, String topic,
            String activityId, String label, Set<String> gatewayVars) {
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

        // Fail explicitly if a required gateway variable was not set by ANY of this worker's
        // legitimate producers - CapabilityDispatcher or a registered GatewayOutputProvider (or left
        // for a hand-edited version of this worker to set directly). Both are tried; only if neither
        // supplied a variable does completion fail on it.
        StringBuilder varLines = new StringBuilder();
        for (String varName : gatewayVars) {
            varLines.append("            if (!variables.containsKey(\"").append(varName).append("\")) {\n")
                    .append("                throw new IllegalStateException(\"Gateway variable '")
                    .append(varName).append("' must be set by a legitimate producer — bind a capability ")
                    .append("provider to this activity, or register a @Component implementing ")
                    .append("GatewayOutputProvider for topic '").append(topic)
                    .append("', or set it directly in this worker\");\n            }\n");
        }

        String safeLabel = sanitizeForJavaString(label);
        String safeActivityId = sanitizeForJavaString(activityId == null || activityId.isBlank() ? topic
                : activityId);

        // Tries the SAME generic P7 Step 5 capability runtime the Twin worker (renderTwinWorkerSource)
        // already uses first - CapabilityDispatcher resolving this process instance's own
        // evolvedAgent_*/evolvedAgentType_* variables or a cached Workbench-authoritative binding -
        // before falling through to the pre-existing GatewayOutputProvider extension point. Both are
        // real, generic, already-proven producers; neither is specific to any one process. A gateway
        // variable that neither produces still fails explicitly below, exactly as before this change.
        return """
                package %1$s;

                import java.util.HashMap;
                import java.util.Map;
                import java.util.Optional;

                import org.camunda.bpm.engine.ExternalTaskService;
                import org.camunda.bpm.engine.RuntimeService;
                import org.camunda.bpm.engine.externaltask.LockedExternalTask;
                import org.slf4j.Logger;
                import org.slf4j.LoggerFactory;
                import org.springframework.beans.factory.ObjectProvider;
                import org.springframework.stereotype.Component;

                import com.metaml.workbench.automation.AutomationResult;
                import com.metaml.workbench.capability.runtime.CapabilityDispatcher;
                import com.metaml.workbench.capability.runtime.ExternalTaskExecutionContext;

                import %6$s.GeneratedExternalTaskWorker;

                // Generated for external-task topic "%2$s" (BPMN activity "%3$s").
                @Component
                public class %4$s implements GeneratedExternalTaskWorker {

                    private static final Logger logger = LoggerFactory.getLogger(%4$s.class);
                    private static final String ACTIVITY_ID = "%7$s";

                    private final ObjectProvider<CapabilityDispatcher> capabilityDispatcherProvider;
                    private final ObjectProvider<GatewayOutputProvider> outputProvider;
                    private final RuntimeService runtimeService;

                    public %4$s(ObjectProvider<CapabilityDispatcher> capabilityDispatcherProvider,
                            ObjectProvider<GatewayOutputProvider> outputProvider, RuntimeService runtimeService) {
                        this.capabilityDispatcherProvider = capabilityDispatcherProvider;
                        this.outputProvider = outputProvider;
                        this.runtimeService = runtimeService;
                    }

                    @Override
                    public String topic() {
                        return "%2$s";
                    }

                    @Override
                    public void execute(LockedExternalTask task, ExternalTaskService externalTaskService) {
                        logger.info("Executing generated external-task worker for activity \\"%8$s\\" "
                                + "(process instance {})", task.getProcessInstanceId());
                        Map<String, Object> variables = new HashMap<>();
                        boolean satisfiedByCapability = false;
                        CapabilityDispatcher dispatcher = capabilityDispatcherProvider.getIfAvailable();
                        if (dispatcher != null) {
                            ExternalTaskExecutionContext context =
                                    new ExternalTaskExecutionContext(task, runtimeService);
                            Optional<AutomationResult> dispatched = dispatcher.dispatch(context, ACTIVITY_ID, null);
                            if (dispatched.isPresent()) {
                                logger.info("Capability-dispatched activity \\"%8$s\\" (process instance {})",
                                        task.getProcessInstanceId());
                                variables.putAll(context.completionVariables());
                                satisfiedByCapability = true;
                            }
                        }
                        if (!satisfiedByCapability) {
                            GatewayOutputProvider provider = outputProvider.getIfAvailable();
                            if (provider != null) {
                                logger.info("Invoking registered GatewayOutputProvider {} for activity \\"%8$s\\" "
                                        + "(process instance {})", provider.getClass().getSimpleName(),
                                        task.getProcessInstanceId());
                                Map<String, Object> provided = provider.provide("%2$s", task);
                                if (provided != null) {
                                    variables.putAll(provided);
                                }
                            }
                        }
                %5$s        logger.info("Worker completion variables: {}", variables);
                        externalTaskService.complete(task.getId(), "generated-worker", variables);
                    }
                }
                """.formatted(packageName, topic, label, className, varLines, workerBasePackage,
                        safeActivityId, safeLabel);
    }

    private static String sanitizeForJavaString(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
