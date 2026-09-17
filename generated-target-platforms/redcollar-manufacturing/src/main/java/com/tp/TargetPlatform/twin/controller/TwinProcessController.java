package com.tp.TargetPlatform.twin.controller;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.engine.runtime.ProcessInstance;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.tp.TargetPlatform.coordination.PairRegistry;
import com.tp.TargetPlatform.portal.RunExecutionGate;

// REST controller starting twin instances and registering their businessKey with PairRegistry.
@RestController
@RequestMapping("/api/twin")
public class TwinProcessController {

    private final RuntimeService runtimeService;
    private final PairRegistry pairRegistry;
    private final RunExecutionGate executionGate;

    public TwinProcessController(RuntimeService runtimeService, PairRegistry pairRegistry, RunExecutionGate executionGate) {
        this.runtimeService = runtimeService;
        this.pairRegistry = pairRegistry;
        this.executionGate = executionGate;
    }

    @GetMapping("/health")
    public String health() {
        return "twin ok";
    }

    // Starts a process instance; matching businessKey enables coordinated signal synchronization.
    @PostMapping("/start")
    public Map<String, Object> start(@RequestParam(required = false) String businessKey,
            @RequestParam(required = false) String executionMode) {
        String key = (businessKey == null || businessKey.isBlank())
                ? UUID.randomUUID().toString() : businessKey;
        if (executionMode != null && !executionMode.isBlank()) {
            executionGate.configure(key, executionMode);
        }
        ProcessInstance instance = runtimeService.startProcessInstanceByKey("RedCollar.Twin", key);
        String role = pairRegistry.registerAndClassify(key, instance.getProcessInstanceId());
        Map<String, Object> body = new HashMap<>();
        body.put("processInstanceId", instance.getProcessInstanceId());
        body.put("businessKey", key);
        body.put("role", role == null ? "unpaired" : role);
        body.put("executionMode", executionGate.mode(key).name());
        return body;
    }
}
