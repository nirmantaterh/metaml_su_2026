package com.tp.TargetPlatform.portal;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.camunda.bpm.engine.ExternalTaskService;
import org.camunda.bpm.engine.HistoryService;
import org.camunda.bpm.engine.RepositoryService;
import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.engine.TaskService;
import org.camunda.bpm.engine.externaltask.ExternalTask;
import org.camunda.bpm.engine.history.HistoricActivityInstance;
import org.camunda.bpm.engine.history.HistoricProcessInstance;
import org.camunda.bpm.engine.repository.ProcessDefinition;
import org.camunda.bpm.engine.runtime.EventSubscription;
import org.camunda.bpm.engine.runtime.Incident;
import org.camunda.bpm.engine.runtime.ProcessInstance;
import org.camunda.bpm.engine.task.Task;
import org.camunda.bpm.model.bpmn.BpmnModelInstance;
import org.camunda.bpm.model.bpmn.instance.Activity;
import org.camunda.bpm.model.bpmn.instance.FlowNode;
import org.camunda.bpm.model.bpmn.instance.SequenceFlow;
import org.camunda.bpm.model.bpmn.instance.StartEvent;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.metaml.workbench.automation.ComponentExecutor;
import com.metaml.workbench.capability.runtime.CapabilityResponseSequences;

// Assembles the portal's views straight out of the engine this JVM is running: RepositoryService for
// what is deployed, RuntimeService/HistoryService for where each instance actually is, TaskService for
// real human work. Nothing is cached, invented, or defaulted to a placeholder - an empty list means
// the engine genuinely has nothing, and the UI says so.
//
// Everything here is keyed on BPMN element identity and engine state. No process key, activity name or
// business term appears anywhere in this class, so it renders an arbitrary enterprise model.
@Service
public class PortalRuntimeService {

    // Matches the lockstep signal naming the Workbench's TargetPlatformSourceGenerator emits on both
    // sides of a generated pair. It is the platform's own generic convention, not a domain term.
    private static final String SYNC_SIGNAL_PREFIX = "sync_";

    private final RepositoryService repositoryService;
    private final RuntimeService runtimeService;
    private final HistoryService historyService;
    private final TaskService taskService;
    private final ExternalTaskService externalTaskService;
    private final List<ComponentExecutor> componentExecutors;
    private final String messagingEnabled;
    private final String rabbitHost;
    private final String rabbitPort;
    private final String workbenchUrl;
    private final ObjectProvider<ConnectionFactory> rabbitConnectionFactory;
    private final RunExecutionGate executionGate;
    private final RuntimeEventLog eventLog;
    private final long startedAt = System.currentTimeMillis();

    public PortalRuntimeService(RepositoryService repositoryService, RuntimeService runtimeService,
            HistoryService historyService, TaskService taskService, ExternalTaskService externalTaskService,
            List<ComponentExecutor> componentExecutors,
            ObjectProvider<ConnectionFactory> rabbitConnectionFactory,
            RunExecutionGate executionGate, RuntimeEventLog eventLog,
            @Value("${metaml.messaging.enabled:false}") String messagingEnabled,
            @Value("${spring.rabbitmq.host:localhost}") String rabbitHost,
            @Value("${spring.rabbitmq.port:5672}") String rabbitPort,
            @Value("${metaml.capability.workbench-url:}") String workbenchUrl) {
        this.repositoryService = repositoryService;
        this.runtimeService = runtimeService;
        this.historyService = historyService;
        this.taskService = taskService;
        this.externalTaskService = externalTaskService;
        this.componentExecutors = componentExecutors;
        this.rabbitConnectionFactory = rabbitConnectionFactory;
        this.executionGate = executionGate;
        this.eventLog = eventLog;
        this.messagingEnabled = messagingEnabled;
        this.rabbitHost = rabbitHost;
        this.rabbitPort = rabbitPort;
        this.workbenchUrl = workbenchUrl;
    }

    public Map<String, Object> completeProcess(String businessKey) {
        executionGate.runAutomatically(businessKey);
        return executionState(businessKey);
    }

    public Map<String, Object> nextStep(String businessKey) {
        executionGate.releaseNext(businessKey);
        return executionState(businessKey);
    }

    public Map<String, Object> executionState(String businessKey) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("businessKey", businessKey);
        state.put("mode", executionGate.mode(businessKey).name());
        state.put("providersUsed", providersUsed(businessKey));
        state.put("capabilityResponseConfiguration", capabilityResponseConfiguration(businessKey));
        return state;
    }

    public Map<String, Object> configureCapabilityResponses(String businessKey,
            Map<String, List<Map<String, Object>>> responses) {
        Map<String, List<Map<String, Object>>> configuration = CapabilityResponseSequences.copyConfiguration(responses);
        if (configuration.isEmpty()) {
            throw new IllegalArgumentException("At least one provider response sequence is required");
        }
        List<String> configuredInstances = new ArrayList<>();
        for (Map<String, Object> pair : lockstepPairs(50, businessKey)) {
            for (String side : List.of("original", "twin")) {
                @SuppressWarnings("unchecked") Map<String, Object> member = (Map<String, Object>) pair.get(side);
                String instanceId = (String) member.get("processInstanceId");
                ProcessInstance activeInstance = runtimeService.createProcessInstanceQuery()
                        .processInstanceId(instanceId).active().singleResult();
                if (activeInstance == null) {
                    throw new IllegalArgumentException("Run pair " + businessKey
                            + " is no longer active; scenario responses are run-local and cannot be reused");
                }
                configuredInstances.add(instanceId);
            }
            for (String instanceId : configuredInstances) {
                // Each side receives an equivalent independent configuration. Cursors are process
                // variables too, so one side can never consume the other side's FIFO entries.
                runtimeService.setVariable(instanceId, CapabilityResponseSequences.CONFIG_VARIABLE,
                        CapabilityResponseSequences.copyConfiguration(configuration));
                runtimeService.removeVariable(instanceId, CapabilityResponseSequences.CURSOR_VARIABLE);
            }
            break;
        }
        if (configuredInstances.isEmpty()) throw new IllegalArgumentException("No run pair found for " + businessKey);
        return Map.of("businessKey", businessKey, "configuredInstances", configuredInstances,
                "providerIdentities", configuration.keySet(), "capabilityResponseConfiguration", configuration);
    }

    // Scenario configuration has no JVM registry. While a pair is active, its own Camunda
    // variables are authoritative. After completion the runtime instance (and therefore the
    // configuration) is gone, which is the intended natural cleanup boundary.
    private Map<String, Object> capabilityResponseConfiguration(String businessKey) {
        for (Map<String, Object> pair : lockstepPairs(50, businessKey)) {
            for (String side : List.of("original", "twin")) {
                @SuppressWarnings("unchecked") Map<String, Object> member = (Map<String, Object>) pair.get(side);
                String instanceId = (String) member.get("processInstanceId");
                if (runtimeService.createProcessInstanceQuery().processInstanceId(instanceId).active().singleResult() == null) {
                    continue;
                }
                Object raw = runtimeService.getVariable(instanceId, CapabilityResponseSequences.CONFIG_VARIABLE);
                if (raw instanceof Map<?, ?> configured) {
                    Map<String, Object> copy = new LinkedHashMap<>();
                    configured.forEach((providerIdentity, sequence) -> {
                        if (providerIdentity instanceof String name) copy.put(name, sequence);
                    });
                    return copy;
                }
            }
        }
        return Map.of();
    }

    // Successful CAPABILITY COMPLETE lines are emitted by CapabilityDispatcher only after the real
    // provider ran and its output boundary accepted the result.  This is therefore stronger than a
    // registry listing or a requested binding, and is naturally scoped by instance id.
    private static final Pattern CAPABILITY_COMPLETE = Pattern.compile(
            "^CAPABILITY COMPLETE: .*?processInstanceId=([^\\s]+).*?providerIdentity=([^\\s]+)");

    public List<String> providersUsed(String businessKey) {
        Set<String> instanceIds = new HashSet<>();
        for (Map<String, Object> pair : lockstepPairs(50, businessKey)) {
            @SuppressWarnings("unchecked") Map<String, Object> original = (Map<String, Object>) pair.get("original");
            @SuppressWarnings("unchecked") Map<String, Object> twin = (Map<String, Object>) pair.get("twin");
            instanceIds.add((String) original.get("processInstanceId"));
            instanceIds.add((String) twin.get("processInstanceId"));
        }
        Set<String> providers = new LinkedHashSet<>();
        for (RuntimeEventLog.Entry entry : eventLog.tail(3000, Set.of("CAPABILITY"))) {
            Matcher match = CAPABILITY_COMPLETE.matcher(entry.message());
            if (match.find() && instanceIds.contains(match.group(1))) providers.add(match.group(2));
        }
        return providers.stream().sorted().toList();
    }

    // A real connectivity probe, not a configuration echo: opens (or reuses) an AMQP connection and
    // reports whether that actually succeeded. spring-boot-starter-amqp always registers a
    // ConnectionFactory bean, so the ObjectProvider is defensive rather than expected to be empty.
    private boolean rabbitConnected() {
        ConnectionFactory factory = rabbitConnectionFactory.getIfAvailable();
        if (factory == null) {
            return false;
        }
        try {
            return factory.createConnection().isOpen();
        } catch (RuntimeException unreachable) {
            return false;
        }
    }

    // -- Overview -------------------------------------------------------------

    public Map<String, Object> overview() {
        List<Map<String, Object>> definitions = new ArrayList<>();
        for (ProcessDefinition definition : repositoryService.createProcessDefinitionQuery()
                .latestVersion().orderByProcessDefinitionKey().asc().list()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", definition.getId());
            row.put("key", definition.getKey());
            row.put("name", definition.getName());
            row.put("version", definition.getVersion());
            row.put("running", runtimeService.createProcessInstanceQuery()
                    .processDefinitionId(definition.getId()).active().count());
            row.put("everStarted", historyService.createHistoricProcessInstanceQuery()
                    .processDefinitionId(definition.getId()).count());
            definitions.add(row);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("processDefinitions", definitions);
        body.put("runningInstances", runtimeService.createProcessInstanceQuery().active().count());
        body.put("completedInstances", historyService.createHistoricProcessInstanceQuery()
                .completed().count());
        body.put("openTasks", taskService.createTaskQuery().active().count());
        body.put("openIncidents", runtimeService.createIncidentQuery().count());
        body.put("messagingEnabled", messagingEnabled);
        body.put("rabbitMq", rabbitHost + ":" + rabbitPort);
        body.put("rabbitConnected", rabbitConnected());
        // Surfaced because "does this platform need the Workbench at runtime" is the whole point of
        // the standalone boundary, and the honest answer is a configuration fact, not an opinion.
        body.put("workbenchUrlConfigured", workbenchUrl == null || workbenchUrl.isBlank()
                ? "not configured - this platform makes no Workbench calls" : workbenchUrl);
        body.put("capabilityProviders", componentExecutors.stream()
                .map(ComponentExecutor::getHandledAgentType).sorted().toList());
        body.put("uptimeSeconds", (System.currentTimeMillis() - startedAt) / 1000);
        return body;
    }

    // -- Process instances ----------------------------------------------------

    public List<Map<String, Object>> instances() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ProcessInstance instance : runtimeService.createProcessInstanceQuery().active().list()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("processInstanceId", instance.getProcessInstanceId());
            row.put("businessKey", instance.getBusinessKey());
            row.put("processDefinitionKey", definitionKeyOf(instance.getProcessDefinitionId()));
            row.put("activeActivityIds", runtimeService.getActiveActivityIds(instance.getId()));
            row.put("awaitingSignals", awaitingSignals(instance.getProcessInstanceId()));
            row.put("incidents", runtimeService.createIncidentQuery()
                    .processInstanceId(instance.getProcessInstanceId()).count());
            row.put("active", true);
            rows.add(row);
        }
        return rows;
    }

    // -- Human tasks (real Camunda TaskService) -------------------------------

    public List<Map<String, Object>> tasks() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Task task : taskService.createTaskQuery().active().orderByTaskCreateTime().asc().list()) {
            ProcessInstance instance = task.getProcessInstanceId() == null ? null
                    : runtimeService.createProcessInstanceQuery()
                            .processInstanceId(task.getProcessInstanceId()).singleResult();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", task.getId());
            row.put("name", task.getName());
            row.put("taskDefinitionKey", task.getTaskDefinitionKey());
            row.put("processInstanceId", task.getProcessInstanceId());
            row.put("businessKey", instance == null ? null : instance.getBusinessKey());
            row.put("processDefinitionKey", definitionKeyOf(task.getProcessDefinitionId()));
            row.put("created", task.getCreateTime() == null ? null : task.getCreateTime().toString());
            row.put("assignee", task.getAssignee());
            rows.add(row);
        }
        return rows;
    }

    // The real thing: Camunda's own task completion, which ends the user task, fires its listeners and
    // advances the token. There is no variable-setting shortcut anywhere in this class.
    public void completeTask(String taskId, Map<String, Object> variables) {
        if (variables == null || variables.isEmpty()) {
            taskService.complete(taskId);
        } else {
            taskService.complete(taskId, variables);
        }
    }

    // -- Starting a process on this platform ----------------------------------

    // Starts any deployed process definition by key, with optional business key and initial variables,
    // through Camunda's own RuntimeService. This exists so a standalone platform can be operated with
    // the Workbench switched off - notably to supply an activity's capability binding identity
    // (evolvedAgent_*/evolvedAgentType_*), which is normally written by the Workbench when it binds a
    // provider, and which nothing else in a Workbench-less deployment can provide.
    //
    // It supplies BINDING, never OUTCOME: which provider runs, not what that provider decides. The
    // provider still executes for real and still produces its own outputs.
    //
    // It deliberately does NOT register the instance with the generated PairRegistry, so it is not a
    // way to start a lockstep pair - the generated /api/proxy/start and /api/twin/start controllers
    // remain the only entry points that establish the initiator/responder pairing.
    public Map<String, Object> startProcess(String processDefinitionKey, String businessKey,
            Map<String, Object> variables) {
        ProcessInstance instance = runtimeService.startProcessInstanceByKey(processDefinitionKey,
                businessKey == null || businessKey.isBlank() ? null : businessKey,
                variables == null ? Map.of() : variables);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("processInstanceId", instance.getProcessInstanceId());
        body.put("processDefinitionKey", processDefinitionKey);
        body.put("businessKey", instance.getBusinessKey());
        body.put("paired", false);
        return body;
    }

    // Supplies a capability binding on an ALREADY-RUNNING instance (a pair started through the real,
    // pairing-registering /api/proxy/start + /api/twin/start), then resets retries on any of its
    // external tasks currently at zero - the same real recovery path GeneratedProcessStatusController's
    // own retryExternalTask exposes, just found generically by process instance rather than requiring
    // the caller to already know the external task id.
    //
    // This is the same P7 Step 5 binding mechanism startProcess documents above (an
    // evolvedAgentType_<activityId> process variable), applied after start instead of at start, for
    // exactly the case start-time variables cannot reach: a pair that must go through the generated,
    // PairRegistry-registering start endpoints to synchronize correctly at all. It supplies BINDING,
    // never OUTCOME - the provider named still executes for real and still decides its own output.
    public Map<String, Object> bindCapabilityAndRetry(String processInstanceId, String activityId,
            String providerType) {
        String variableName = "evolvedAgentType_" + activityId;
        runtimeService.setVariable(processInstanceId, variableName, providerType);
        List<String> retried = new ArrayList<>();
        for (ExternalTask task : externalTaskService.createExternalTaskQuery()
                .processInstanceId(processInstanceId).list()) {
            if (task.getRetries() != null && task.getRetries() == 0) {
                externalTaskService.setRetries(task.getId(), 3);
                retried.add(task.getId());
            }
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("processInstanceId", processInstanceId);
        body.put("variableSet", variableName);
        body.put("providerType", providerType);
        body.put("externalTasksRetried", retried);
        return body;
    }

    // -- Lockstep -------------------------------------------------------------

    // Pairs process instances purely by shared businessKey, exactly as the generated PairRegistry does,
    // and designates the earlier-started instance the Original - the same "first to start is the
    // initiator" rule the running SignalBroadcaster protocol already uses. No naming convention, no
    // process-key suffix and no domain knowledge is relied on.
    //
    // businessKeyFilter, when given, restricts the result to that one pair (still applying the same
    // pairing rule) rather than requiring the caller to fetch every pair to find one by key.
    public List<Map<String, Object>> lockstepPairs(int limit, String businessKeyFilter) {
        Map<String, List<HistoricProcessInstance>> byBusinessKey = new LinkedHashMap<>();
        List<HistoricProcessInstance> all = historyService.createHistoricProcessInstanceQuery()
                .orderByProcessInstanceStartTime().desc().listPage(0, Math.max(limit * 4, 40));
        for (HistoricProcessInstance instance : all) {
            if (instance.getBusinessKey() == null || instance.getBusinessKey().isBlank()) {
                continue;
            }
            if (businessKeyFilter != null && !businessKeyFilter.isBlank()
                    && !businessKeyFilter.equals(instance.getBusinessKey())) {
                continue;
            }
            byBusinessKey.computeIfAbsent(instance.getBusinessKey(), key -> new ArrayList<>()).add(instance);
        }
        List<Map<String, Object>> pairs = new ArrayList<>();
        for (Map.Entry<String, List<HistoricProcessInstance>> entry : byBusinessKey.entrySet()) {
            List<HistoricProcessInstance> members = new ArrayList<>(entry.getValue());
            if (members.size() < 2) {
                continue;
            }
            members.sort(Comparator.comparing(HistoricProcessInstance::getStartTime));
            HistoricProcessInstance original = members.get(0);
            HistoricProcessInstance twin = members.get(1);
            pairs.add(pair(entry.getKey(), original, twin));
            if (pairs.size() >= limit) {
                break;
            }
        }
        return pairs;
    }

    private Map<String, Object> pair(String businessKey, HistoricProcessInstance original,
            HistoricProcessInstance twin) {
        SideState originalState = sideState(original);
        SideState twinState = sideState(twin);

        // Logical order comes from walking the Original's deployed BPMN, so "activity N" is the model's
        // own order rather than whatever order the engine happened to record.
        List<String> ordered = flowOrderedActivityIds(original.getProcessDefinitionId());
        Set<String> twinActivityIds = activityIds(twin.getProcessDefinitionId());
        Map<String, String> originalNames = activityNames(original.getProcessDefinitionId());
        Map<String, String> twinNames = activityNames(twin.getProcessDefinitionId());

        List<Map<String, Object>> steps = new ArrayList<>();
        int index = 0;
        for (String activityId : ordered) {
            if (!twinActivityIds.contains(activityId)) {
                // Not mirrored by the Twin, so there is no correspondence to display for it.
                continue;
            }
            Map<String, Object> step = new LinkedHashMap<>();
            step.put("index", index++);
            step.put("activityId", activityId);
            step.put("originalName", originalNames.getOrDefault(activityId, activityId));
            step.put("twinName", twinNames.getOrDefault(activityId, activityId));
            step.put("originalState", stateOf(originalState, activityId));
            step.put("twinState", stateOf(twinState, activityId));
            steps.add(step);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("businessKey", businessKey);
        body.put("original", sideSummary(original, originalState));
        body.put("twin", sideSummary(twin, twinState));
        body.put("steps", steps);
        return body;
    }

    private Map<String, Object> sideSummary(HistoricProcessInstance instance, SideState state) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("processInstanceId", instance.getId());
        body.put("processDefinitionId", instance.getProcessDefinitionId());
        body.put("processDefinitionKey", instance.getProcessDefinitionKey());
        body.put("active", instance.getEndTime() == null);
        body.put("startTime", instance.getStartTime() == null ? null : instance.getStartTime().toString());
        body.put("endTime", instance.getEndTime() == null ? null : instance.getEndTime().toString());
        body.put("activeActivityIds", state.activeActivityIds());
        body.put("awaitingSignals", state.awaitingSignals());
        body.put("incidentActivityIds", state.incidentActivityIds());
        return body;
    }

    private record SideState(Set<String> activeActivityIds, Set<String> awaitingSignals,
            Set<String> startedActivityIds, Set<String> endedActivityIds, Set<String> incidentActivityIds) { }

    private SideState sideState(HistoricProcessInstance instance) {
        Set<String> active = new LinkedHashSet<>();
        Set<String> signals = new LinkedHashSet<>();
        Set<String> incidents = new LinkedHashSet<>();
        if (instance.getEndTime() == null) {
            try {
                active.addAll(runtimeService.getActiveActivityIds(instance.getId()));
            } catch (RuntimeException alreadyGone) {
                // The instance ended between the history read and this call; an empty set is correct.
            }
            signals.addAll(awaitingSignals(instance.getId()));
            // Real Camunda incident state (job retries exhausted, etc.) - the same query the
            // generated status controller and SignalBroadcaster's own stuck-partner detection use.
            for (Incident incident : runtimeService.createIncidentQuery()
                    .processInstanceId(instance.getId()).list()) {
                if (incident.getActivityId() != null) {
                    incidents.add(incident.getActivityId());
                }
            }
        }
        Set<String> started = new LinkedHashSet<>();
        Set<String> ended = new LinkedHashSet<>();
        for (HistoricActivityInstance activity : historyService.createHistoricActivityInstanceQuery()
                .processInstanceId(instance.getId()).list()) {
            started.add(activity.getActivityId());
            if (activity.getEndTime() != null) {
                ended.add(activity.getActivityId());
            }
        }
        return new SideState(active, signals, started, ended, incidents);
    }

    // WAITING_SYNC is the state that makes the invariant visible: this side has finished its own
    // activity N (or has not been allowed to begin it) and is parked on sync_<activityId> until its
    // partner reaches the same rendezvous. INCIDENT takes priority over ACTIVE - an activity stuck on
    // a genuine Camunda incident is not merely "in progress".
    private static String stateOf(SideState state, String activityId) {
        if (state.incidentActivityIds().contains(activityId)) {
            return "INCIDENT";
        }
        if (state.awaitingSignals().contains(SYNC_SIGNAL_PREFIX + activityId)) {
            return "WAITING_SYNC";
        }
        if (state.activeActivityIds().contains(activityId)) {
            return "ACTIVE";
        }
        if (state.endedActivityIds().contains(activityId)) {
            return "COMPLETED";
        }
        if (state.startedActivityIds().contains(activityId)) {
            return "ACTIVE";
        }
        return "PENDING";
    }

    private Set<String> awaitingSignals(String processInstanceId) {
        Set<String> names = new LinkedHashSet<>();
        for (EventSubscription subscription : runtimeService.createEventSubscriptionQuery()
                .processInstanceId(processInstanceId).eventType("signal").list()) {
            names.add(subscription.getEventName());
        }
        return names;
    }

    // The RAW deployed BPMN resource bytes (including its original DI) for the latest version of this
    // process key - not a reconstruction via Bpmn.convertToString, so what renders is exactly what was
    // deployed. null when no such definition is deployed; the caller decides how to report that.
    public String bpmnXml(String processDefinitionKey) {
        ProcessDefinition definition = repositoryService.createProcessDefinitionQuery()
                .processDefinitionKey(processDefinitionKey).latestVersion().singleResult();
        if (definition == null) {
            return null;
        }
        try (InputStream in = repositoryService.getResourceAsStream(definition.getDeploymentId(),
                definition.getResourceName())) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(
                    "Could not read deployed BPMN resource for " + processDefinitionKey, e);
        }
    }

    // -- BPMN model helpers ---------------------------------------------------

    // Breadth-first from every start event along sequence flows, so activities come back in the order
    // the model actually reaches them. Visited-guarded, so loops and joins terminate.
    private List<String> flowOrderedActivityIds(String processDefinitionId) {
        List<String> ordered = new ArrayList<>();
        BpmnModelInstance model = bpmnModel(processDefinitionId);
        if (model == null) {
            return ordered;
        }
        Set<String> visited = new HashSet<>();
        ArrayDeque<FlowNode> queue = new ArrayDeque<>();
        for (StartEvent start : model.getModelElementsByType(StartEvent.class)) {
            queue.add(start);
        }
        while (!queue.isEmpty()) {
            FlowNode node = queue.poll();
            if (node.getId() == null || !visited.add(node.getId())) {
                continue;
            }
            if (node instanceof Activity) {
                ordered.add(node.getId());
            }
            for (SequenceFlow flow : node.getOutgoing()) {
                FlowNode target = flow.getTarget();
                if (target != null) {
                    queue.add(target);
                }
            }
        }
        return ordered;
    }

    private Set<String> activityIds(String processDefinitionId) {
        Set<String> ids = new LinkedHashSet<>();
        BpmnModelInstance model = bpmnModel(processDefinitionId);
        if (model == null) {
            return ids;
        }
        for (Activity activity : model.getModelElementsByType(Activity.class)) {
            ids.add(activity.getId());
        }
        return ids;
    }

    private Map<String, String> activityNames(String processDefinitionId) {
        Map<String, String> names = new HashMap<>();
        BpmnModelInstance model = bpmnModel(processDefinitionId);
        if (model == null) {
            return names;
        }
        for (Activity activity : model.getModelElementsByType(Activity.class)) {
            names.put(activity.getId(),
                    activity.getName() == null || activity.getName().isBlank()
                            ? activity.getId() : activity.getName());
        }
        return names;
    }

    private BpmnModelInstance bpmnModel(String processDefinitionId) {
        try {
            return repositoryService.getBpmnModelInstance(processDefinitionId);
        } catch (RuntimeException notAvailable) {
            return null;
        }
    }

    private String definitionKeyOf(String processDefinitionId) {
        if (processDefinitionId == null) {
            return null;
        }
        ProcessDefinition definition = repositoryService.createProcessDefinitionQuery()
                .processDefinitionId(processDefinitionId).singleResult();
        return definition == null ? processDefinitionId : definition.getKey();
    }
}
