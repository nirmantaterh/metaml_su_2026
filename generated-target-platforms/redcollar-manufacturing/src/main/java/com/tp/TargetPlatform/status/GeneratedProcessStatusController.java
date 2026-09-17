package com.tp.TargetPlatform.status;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.camunda.bpm.engine.ExternalTaskService;
import org.camunda.bpm.engine.HistoryService;
import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.engine.externaltask.LockedExternalTask;
import org.camunda.bpm.engine.runtime.ProcessInstance;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// Generic, read-only process-instance introspection - works for any deployed process, not just one this project's own generated controllers know about. Backs causal test assertions against real engine state (active activities, process variables, business key) rather than log text. Reliability hardening (Pass 2): also exposes genuine Camunda incident state (not simulated) - both to read it (incidents(), the same real state SignalBroadcaster.awaitingResponse's stuck-partner detection now checks) and, since this generated project has no camunda-bpm-spring-boot-starter-rest dependency of its own (no /engine-rest to reach for this), to deliberately induce and later resolve a REAL incident for failure-injection testing (failExternalTaskPermanently / retryExternalTask). Neither touches Proxy/Twin synchronization logic itself - both operate on whatever process instance/external task the caller names.
@RestController
@RequestMapping("/api/v1/process")
public class GeneratedProcessStatusController {

    private final RuntimeService runtimeService;
    private final HistoryService historyService;
    private final ExternalTaskService externalTaskService;

    public GeneratedProcessStatusController(RuntimeService runtimeService,
            HistoryService historyService, ExternalTaskService externalTaskService) {
        this.runtimeService = runtimeService;
        this.historyService = historyService;
        this.externalTaskService = externalTaskService;
    }

    @GetMapping("/{processInstanceId}/status")
    public ResponseEntity<Map<String, Object>> status(@PathVariable String processInstanceId) {
        ProcessInstance instance = runtimeService.createProcessInstanceQuery()
                .processInstanceId(processInstanceId)
                .singleResult();
        if (instance == null) {
            Map<String, Object> inactive = new HashMap<>();
            inactive.put("active", false);
            return ResponseEntity.ok(inactive);
        }
        List<String> activeActivityIds = runtimeService.getActiveActivityIds(processInstanceId);
        Map<String, Object> variables = runtimeService.getVariables(processInstanceId);
        Map<String, Object> body = new HashMap<>();
        body.put("active", true);
        body.put("activeActivityIds", activeActivityIds);
        body.put("variables", variables);
        body.put("businessKey", instance.getBusinessKey());
        return ResponseEntity.ok(body);
    }

    // How many times this process instance has ever entered the given BPMN activity, whether still active or long since completed - authoritative proof of a rework loop (or any other repeat visit), independent of process definition or activity shape. Uses HistoryService (camunda.bpm.history-level=full by default), not the in-memory active-activity view, precisely because a repeat visit's earlier instances are no longer "active" by the time anyone asks.
    @GetMapping("/{processInstanceId}/activity-history/{activityId}/count")
    public ResponseEntity<Map<String, Object>> activityVisitCount(
            @PathVariable String processInstanceId, @PathVariable String activityId) {
        long count = historyService.createHistoricActivityInstanceQuery()
                .processInstanceId(processInstanceId)
                .activityId(activityId)
                .count();
        Map<String, Object> body = new HashMap<>();
        body.put("processInstanceId", processInstanceId);
        body.put("activityId", activityId);
        body.put("visitCount", count);
        return ResponseEntity.ok(body);
    }

    // Real Camunda incident state for a process instance - the same query SignalBroadcaster's own stuck-partner detection (Pass 2) runs, exposed read-only so a caller (a test, an operator) can see it too instead of only inferring it from logs.
    @GetMapping("/{processInstanceId}/incidents/count")
    public ResponseEntity<Map<String, Object>> incidentCount(
            @PathVariable String processInstanceId) {
        long count = runtimeService.createIncidentQuery()
                .processInstanceId(processInstanceId)
                .count();
        Map<String, Object> body = new HashMap<>();
        body.put("processInstanceId", processInstanceId);
        body.put("incidentCount", count);
        return ResponseEntity.ok(body);
    }

    // Test-support: deliberately fails a real, currently-lockable external task for the given topic on the given process instance, with retries=0 - this is a genuine Camunda incident (job retries exhausted), not a simulated one, produced through the same ExternalTaskService API a real worker uses, just reporting failure instead of completing. Exists because this generated project has no /engine-rest of its own to do this from outside the JVM.
    @PostMapping("/{processInstanceId}/external-task/{topic}/fail-permanently")
    public ResponseEntity<Map<String, Object>> failExternalTaskPermanently(
            @PathVariable String processInstanceId, @PathVariable String topic) {
        // ExternalTaskQueryTopicBuilder has no processInstanceId filter of its own (only businessKey/processDefinitionId/Key) - resolving the instance's own business key first and filtering on THAT is what actually scopes this to the right instance, not just the right topic (which alone would still work for a single pair, but not when more than one pair shares a topic name concurrently).
        ProcessInstance target = runtimeService.createProcessInstanceQuery()
                .processInstanceId(processInstanceId)
                .singleResult();
        if (target == null) {
            Map<String, Object> notFound = new HashMap<>();
            notFound.put("error", "no active process instance " + processInstanceId);
            return ResponseEntity.status(404).body(notFound);
        }
        List<LockedExternalTask> locked = externalTaskService
                .fetchAndLock(1, "test-failure-injector")
                .topic(topic, 60000)
                .businessKey(target.getBusinessKey())
                .execute();
        if (locked.isEmpty()) {
            Map<String, Object> notFound = new HashMap<>();
            notFound.put("error", "no lockable external task for topic '" + topic
                    + "' on process instance " + processInstanceId);
            return ResponseEntity.status(404).body(notFound);
        }
        String externalTaskId = locked.get(0).getId();
        externalTaskService.handleFailure(externalTaskId, "test-failure-injector",
                "Deliberately failed by a test to produce a real Camunda incident", 0, 0L);
        Map<String, Object> body = new HashMap<>();
        body.put("externalTaskId", externalTaskId);
        body.put("topic", topic);
        body.put("processInstanceId", processInstanceId);
        return ResponseEntity.ok(body);
    }

    // Test-support: the real recovery path for the incident failExternalTaskPermanently produces - restoring retries is what lets Camunda's own job executor pick the external task back up and, since the generated worker's own logic never deliberately fails, complete it normally on the next attempt. Genuine recovery through real Camunda mechanics, not a test-only shortcut that pretends the task completed.
    @PostMapping("/external-task/{externalTaskId}/retry")
    public ResponseEntity<Map<String, Object>> retryExternalTask(
            @PathVariable String externalTaskId) {
        externalTaskService.setRetries(externalTaskId, 3);
        Map<String, Object> body = new HashMap<>();
        body.put("externalTaskId", externalTaskId);
        body.put("retriesSet", 3);
        return ResponseEntity.ok(body);
    }
}
