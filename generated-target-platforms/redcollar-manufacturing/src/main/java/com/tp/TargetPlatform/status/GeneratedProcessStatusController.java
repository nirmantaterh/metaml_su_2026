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

// Generic process-instance introspection for deployed processes. Exposes active activities, process variables,
// business key, and Camunda incident state for runtime diagnostics and failure-injection testing.
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

    // Query activity visit count across execution history.
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

    // Exposes active Camunda incidents for a process instance to monitor workflow execution state.
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

    // Fails a lockable external task for the given topic on the process instance to test incident handling.
    @PostMapping("/{processInstanceId}/external-task/{topic}/fail-permanently")
    public ResponseEntity<Map<String, Object>> failExternalTaskPermanently(
            @PathVariable String processInstanceId, @PathVariable String topic) {
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
                "Deliberately failed by test to produce a Camunda incident", 0, 0L);
        Map<String, Object> body = new HashMap<>();
        body.put("externalTaskId", externalTaskId);
        body.put("topic", topic);
        body.put("processInstanceId", processInstanceId);
        return ResponseEntity.ok(body);
    }

    // Diagnostic endpoint to reset retries for an external task, allowing the Camunda job executor to re-attempt execution.
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
