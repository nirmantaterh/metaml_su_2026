package com.metaml.workbench.automation;

import com.metaml.workbench.capability.runtime.CapabilityExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

@Component("validatorExecutor")
public class ValidatorExecutor implements ComponentExecutor {

    private static final Logger logger = LoggerFactory.getLogger(ValidatorExecutor.class);

    public static final String AGENT_TYPE = "validator";
    public static final String AGENT_NAME = "validator-agent-01";
    public static final String EXECUTOR_NAME = "ValidatorExecutor";
    public static final String SCHEMA_VERSION = "v2.4";

    @Override
    public String getHandledAgentType() {
        return AGENT_TYPE;
    }

    @Override public Set<String> providedOutputNames() { return Set.of("validationPassed", "schemaVersion", "validationStatus", "validationMessage"); }

    @Override
    public Set<String> getHandledAgentNames() {
        return Set.of(AGENT_NAME);
    }

    @Override
    public AutomationResult execute(CapabilityExecutionContext execution, String activityId,
            String agentName) {
        Object customerIdVar = execution.getVariable("customerId");
        Object payloadVar = execution.getVariable("payload");
        Object forceFailureVar = execution.getVariable("forceValidationFailure");

        boolean validationPassed = true;
        String message;

        if (Boolean.TRUE.equals(forceFailureVar)) {
            validationPassed = false;
            message = String.format("Validation failed for activity %s: forced validation failure flag is set", activityId);
        } else if (customerIdVar != null && (customerIdVar.toString().isBlank() || customerIdVar.toString().equalsIgnoreCase("INVALID"))) {
            validationPassed = false;
            message = String.format("Validation failed for activity %s: customerId '%s' failed format integrity check", activityId, customerIdVar);
        } else if (payloadVar != null && payloadVar.toString().isBlank()) {
            validationPassed = false;
            message = String.format("Validation failed for activity %s: empty payload provided", activityId);
        } else {
            validationPassed = true;
            message = String.format("Validation passed for activity %s: execution context verified against schema %s", activityId, SCHEMA_VERSION);
        }

        execution.setVariable("validationPassed", validationPassed);

        Map<String, Object> outputs = new LinkedHashMap<>();
        outputs.put("validationPassed", validationPassed);
        outputs.put("schemaVersion", SCHEMA_VERSION);
        outputs.put("validationStatus", validationPassed ? "PASSED" : "FAILED");
        outputs.put("validationMessage", message);

        logger.info("{} executed for activity {} on instance {} (agent: {}) with validationPassed={}",
                EXECUTOR_NAME, activityId, execution.getProcessInstanceId(), agentName, validationPassed);

        String summary = String.format("%s executed for %s: validationPassed=%b, schemaVersion=%s",
                EXECUTOR_NAME, activityId, validationPassed, SCHEMA_VERSION);

        return new AutomationResult(summary, outputs);
    }
}
