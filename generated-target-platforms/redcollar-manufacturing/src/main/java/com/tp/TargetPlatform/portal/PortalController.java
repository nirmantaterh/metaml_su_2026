package com.tp.TargetPlatform.portal;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.camunda.bpm.engine.ProcessEngineException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// The standalone Target Platform's own runtime API, backing the static portal served from this same
// application. Every endpoint reads (or, for task completion, drives) the embedded engine directly -
// there is no Workbench call anywhere in this class, and the portal it serves keeps working with the
// Workbench shut down.
@RestController
@RequestMapping("/api/portal")
public class PortalController {

    private static final Logger logger = LoggerFactory.getLogger(PortalController.class);

    private final PortalRuntimeService runtime;
    private final RuntimeEventLog eventLog;

    public PortalController(PortalRuntimeService runtime, RuntimeEventLog eventLog) {
        this.runtime = runtime;
        this.eventLog = eventLog;
    }

    @GetMapping("/overview")
    public Map<String, Object> overview() {
        return runtime.overview();
    }

    @GetMapping("/instances")
    public List<Map<String, Object>> instances() {
        return runtime.instances();
    }

    @GetMapping("/providers/technical-modes")
    public List<Map<String, Object>> providerTechnicalModes() {
        return runtime.providerTechnicalModes();
    }

    @PostMapping("/providers/{providerIdentity}/technical-mode")
    public ResponseEntity<Map<String, Object>> setProviderTechnicalMode(@PathVariable String providerIdentity,
            @RequestBody Map<String, String> body) {
        try {
            return ResponseEntity.ok(runtime.setProviderTechnicalMode(providerIdentity, body.get("technicalMode")));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/tasks")
    public List<Map<String, Object>> tasks() {
        return runtime.tasks();
    }

    @GetMapping("/runs/{businessKey}/execution")
    public Map<String, Object> execution(@PathVariable String businessKey) {
        return runtime.executionState(businessKey);
    }

    // These controls only release real generated workers.  They neither complete BPMN work nor
    // bypass human tasks, RabbitMQ, capability dispatch, or the pair synchronization protocol.
    @PostMapping("/runs/{businessKey}/complete")
    public Map<String, Object> completeProcess(@PathVariable String businessKey) {
        return runtime.completeProcess(businessKey);
    }

    @PostMapping("/runs/{businessKey}/next")
    public Map<String, Object> nextStep(@PathVariable String businessKey) {
        return runtime.nextStep(businessKey);
    }

    @PostMapping("/runs/{businessKey}/capability-responses")
    public ResponseEntity<Map<String, Object>> configureCapabilityResponses(@PathVariable String businessKey,
            @RequestBody Map<String, List<Map<String, Object>>> responses) {
        try {
            return ResponseEntity.ok(runtime.configureCapabilityResponses(businessKey, responses));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // Completes a REAL Camunda user task through TaskService. The response reports the engine's own
    // outcome; a task that cannot be completed returns the engine's error rather than a success the
    // UI could misread as progress.
    @PostMapping("/tasks/{taskId}/complete")
    public ResponseEntity<Map<String, Object>> completeTask(@PathVariable String taskId,
            @RequestBody(required = false) Map<String, Object> variables) {
        try {
            runtime.completeTask(taskId, variables);
        } catch (ProcessEngineException e) {
            logger.error("PORTAL: task completion FAILED for taskId={}: {}", taskId, e.toString());
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("taskId", taskId);
            error.put("completed", false);
            error.put("error", e.getMessage());
            return ResponseEntity.status(409).body(error);
        }
        logger.info("PORTAL: completed Camunda user task {} via TaskService", taskId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("taskId", taskId);
        body.put("completed", true);
        return ResponseEntity.ok(body);
    }

    // Starts a deployed process definition through RuntimeService, optionally with initial variables.
    // See PortalRuntimeService.startProcess for what this is and is not for: it does not pair
    // instances, so it is not an alternative to the generated proxy/twin start controllers.
    @PostMapping("/processes/{processDefinitionKey}/start")
    public ResponseEntity<Map<String, Object>> startProcess(@PathVariable String processDefinitionKey,
            @RequestParam(required = false) String businessKey,
            @RequestBody(required = false) Map<String, Object> variables) {
        try {
            return ResponseEntity.ok(runtime.startProcess(processDefinitionKey, businessKey, variables));
        } catch (ProcessEngineException e) {
            logger.error("PORTAL: could not start process '{}': {}", processDefinitionKey, e.toString());
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("processDefinitionKey", processDefinitionKey);
            error.put("started", false);
            error.put("error", e.getMessage());
            return ResponseEntity.status(409).body(error);
        }
    }

    // Test/demo-support, generic: supplies a capability binding on an already-running instance and
    // retries any of its zero-retry external tasks through the real job executor. See
    // PortalRuntimeService.bindCapabilityAndRetry for what this is and is not for.
    @PostMapping("/instances/{processInstanceId}/capability-binding")
    public ResponseEntity<Map<String, Object>> bindCapability(@PathVariable String processInstanceId,
            @RequestBody Map<String, String> body) {
        String activityId = body.get("activityId");
        String providerType = body.get("providerType");
        if (activityId == null || activityId.isBlank() || providerType == null || providerType.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        try {
            return ResponseEntity.ok(runtime.bindCapabilityAndRetry(processInstanceId, activityId, providerType));
        } catch (ProcessEngineException e) {
            logger.error("PORTAL: could not bind capability for instance {}: {}", processInstanceId, e.toString());
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("processInstanceId", processInstanceId);
            error.put("bound", false);
            error.put("error", e.getMessage());
            return ResponseEntity.status(409).body(error);
        }
    }

    @GetMapping("/lockstep")
    public List<Map<String, Object>> lockstep(@RequestParam(defaultValue = "10") int limit,
            @RequestParam(required = false) String businessKey) {
        return runtime.lockstepPairs(Math.max(1, Math.min(limit, 50)), businessKey);
    }

    // The raw deployed BPMN XML (with its original DI) for a process definition key, so the portal can
    // render the actual model rather than redrawing it. 404 when nothing by that key is deployed.
    @GetMapping("/bpmn/{processDefinitionKey}")
    public ResponseEntity<String> bpmn(@PathVariable String processDefinitionKey) {
        String xml = runtime.bpmnXml(processDefinitionKey);
        if (xml == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_XML)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(xml);
    }

    // The messaging view: only entries the generated RabbitMQ publishers/listeners and the signal
    // layer actually emitted. No entry is synthesised for a message that was never sent.
    @GetMapping("/messages")
    public List<RuntimeEventLog.Entry> messages(@RequestParam(defaultValue = "200") int limit) {
        return eventLog.tail(Math.max(1, Math.min(limit, 1000)),
                Set.of("TASK", "RESPONSE", "SIGNAL"));
    }

    @GetMapping("/logs")
    public List<RuntimeEventLog.Entry> logs(@RequestParam(defaultValue = "300") int limit,
            @RequestParam(required = false) String kinds) {
        Set<String> selected = kinds == null || kinds.isBlank() ? Set.of()
                : Arrays.stream(kinds.split(",")).map(String::trim).filter(s -> !s.isEmpty())
                        .collect(Collectors.toSet());
        return eventLog.tail(Math.max(1, Math.min(limit, 2000)), selected);
    }
}
