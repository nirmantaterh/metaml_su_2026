package com.metaml.workbench.automation;

import com.metaml.workbench.capability.runtime.CapabilityExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

@Component("creditRiskAssessorExecutor")
public class CreditRiskAssessorExecutor implements ComponentExecutor {

    private static final Logger logger = LoggerFactory.getLogger(CreditRiskAssessorExecutor.class);

    public static final String AGENT_TYPE = "credit-risk-assessor";
    public static final String AGENT_NAME = "credit-risk-agent-01";
    public static final String AGENT_NAME_STUB = "credit-risk-assessor-agent-01";
    public static final String EXECUTOR_NAME = "CreditRiskAssessorExecutor";
    public static final int DEFAULT_RISK_THRESHOLD = 50;

    @Override
    public String getHandledAgentType() {
        return AGENT_TYPE;
    }

    @Override
    public Set<String> getHandledAgentNames() {
        return Set.of(AGENT_NAME, AGENT_NAME_STUB);
    }


    @Override
    public AutomationResult execute(CapabilityExecutionContext execution, String activityId,
            String agentName) {
        Object amountVar = execution.getVariable("transferAmount");
        if (amountVar == null) {
            amountVar = execution.getVariable("amount");
        }
        Object creditScoreVar = execution.getVariable("creditScore");
        Object thresholdVar = execution.getVariable("riskThreshold");

        int threshold = DEFAULT_RISK_THRESHOLD;
        if (thresholdVar instanceof Number num) {
            threshold = num.intValue();
        }

        int riskScore;
        String reason;

        if (creditScoreVar instanceof Number creditNum) {
            int creditScore = creditNum.intValue();
            double amount = (amountVar instanceof Number num) ? num.doubleValue() : 5000.0;
            if (creditScore >= 750 && amount <= 10000.0) {
                riskScore = 20;
                reason = String.format("Low risk: High credit score (%d) with low transfer amount ($%.2f)", creditScore, amount);
            } else if (creditScore >= 700 && amount <= 25000.0) {
                riskScore = 35;
                reason = String.format("Low-moderate risk: Good credit score (%d) with standard transfer amount ($%.2f)", creditScore, amount);
            } else if (creditScore < 620 || amount > 50000.0) {
                riskScore = 90;
                reason = String.format("High risk: Credit score (%d) below subprime threshold or large transfer ($%.2f)", creditScore, amount);
            } else {
                riskScore = 65;
                reason = String.format("Moderate risk: Moderate credit score (%d) for transfer amount ($%.2f)", creditScore, amount);
            }
        } else if (amountVar instanceof Number num) {
            double amount = num.doubleValue();
            if (amount > 10000.0) {
                riskScore = (amount > 50000.0) ? 92 : 85;
                reason = String.format("Elevated risk: High transfer volume ($%.2f) exceeds standard automated threshold", amount);
            } else {
                riskScore = 30;
                reason = String.format("Low risk: Transfer volume ($%.2f) within standard operational tolerance", amount);
            }
        } else {
            riskScore = 85;
            reason = "Baseline financial risk assessment for unparameterized transaction context";
        }

        boolean riskFlagged = riskScore >= threshold;

        execution.setVariable("agentFlaggedRisk", riskFlagged);
        execution.setVariable("agentRiskScore", riskScore);

        Map<String, Object> outputs = new LinkedHashMap<>();
        outputs.put("riskScore", riskScore);
        outputs.put("riskFlagged", riskFlagged);
        outputs.put("riskThreshold", threshold);
        outputs.put("assessmentReason", reason);

        logger.info("{} executed for activity {} on instance {} (agent: {}) with riskScore={}, riskFlagged={}",
                EXECUTOR_NAME, activityId, execution.getProcessInstanceId(), agentName, riskScore, riskFlagged);

        String summary = String.format("%s executed for %s: riskScore=%d, riskFlagged=%b",
                EXECUTOR_NAME, activityId, riskScore, riskFlagged);

        return new AutomationResult(summary, outputs);
    }
}
