package com.metaml.workbench.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.metaml.workbench.model.GovernanceDecision;
import com.metaml.workbench.model.GovernancePolicy;
import com.metaml.workbench.model.GovernanceUsage;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Service
public class GovernanceServiceImpl implements GovernanceService {

    // Enforces in-memory platform quotas for concurrent twins and per-twin evolutions.
    private volatile Set<String> deniedAgentTypes = Set.of();
    private volatile int maxEvolutionsPerTwin;
    private volatile int maxTwinExecutionsPerTwin;
    private final Map<String, AtomicInteger> evolutionCounts = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> twinExecutionCounts = new ConcurrentHashMap<>();

    // Properties, not constants: how many evolutions a twin needs depends on how big its process is.
    // The twin-execution default is far higher because it counts every activity the twin's token
    // walks through, gateways included. updatePolicy still overrides both at runtime.
    public GovernanceServiceImpl(
            @Value("${workbench.governance.max-evolutions-per-twin:25}") int maxEvolutionsPerTwin,
            @Value("${workbench.governance.max-twin-executions-per-twin:200}") int maxTwinExecutionsPerTwin) {
        this.maxEvolutionsPerTwin = maxEvolutionsPerTwin;
        this.maxTwinExecutionsPerTwin = maxTwinExecutionsPerTwin;
    }

    @Override
    public GovernancePolicy getPolicy() {
        return new GovernancePolicy(deniedAgentTypes, maxEvolutionsPerTwin, maxTwinExecutionsPerTwin);
    }

    // kept for the callers that predate the twin-execution budget, so they aren't forced to pass a null they don't care about
    @Override
    public GovernancePolicy updatePolicy(Set<String> newDeniedAgentTypes, Integer newMaxEvolutionsPerTwin) {
        return updatePolicy(newDeniedAgentTypes, newMaxEvolutionsPerTwin, null);
    }

    @Override
    public GovernancePolicy updatePolicy(Set<String> newDeniedAgentTypes, Integer newMaxEvolutionsPerTwin,
            Integer newMaxTwinExecutionsPerTwin) {
        // all optional, so you can change one without touching the others
        if (newDeniedAgentTypes != null) {
            deniedAgentTypes = normalize(newDeniedAgentTypes);
        }
        if (newMaxEvolutionsPerTwin != null) {
            if (newMaxEvolutionsPerTwin < 0) {
                throw new IllegalArgumentException("maxEvolutionsPerTwin must not be negative");
            }
            maxEvolutionsPerTwin = newMaxEvolutionsPerTwin;
        }
        if (newMaxTwinExecutionsPerTwin != null) {
            if (newMaxTwinExecutionsPerTwin < 0) {
                throw new IllegalArgumentException("maxTwinExecutionsPerTwin must not be negative");
            }
            maxTwinExecutionsPerTwin = newMaxTwinExecutionsPerTwin;
        }
        return getPolicy();
    }

    // trim + lowercase since this comes straight out of a text box; also filters null, which jackson happily hands us for {"deniedAgentTypes": [null]} and used to NPE
    private static Set<String> normalize(Set<String> agentTypes) {
        return agentTypes.stream()
                .filter(Objects::nonNull)
                .map(type -> type.trim().toLowerCase(Locale.ROOT))
                .filter(type -> !type.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    // Increment then roll back if we went over, not check-then-increment. With a separate check two requests sitting at max-1 both pass it and both take the last slot.
    @Override
    public GovernanceDecision reserveEvolutionSlot(String twinProcessId, String agentType) {
        // same Locale.ROOT as normalize(), or the two sides disagree under a Turkish default
        if (deniedAgentTypes.contains(agentType.trim().toLowerCase(Locale.ROOT))) {
            return new GovernanceDecision(false,
                    "Agent type '" + agentType + "' is denied by governance policy");
        }

        int max = maxEvolutionsPerTwin; // read once, else the message can quote a different number
        AtomicInteger counter = evolutionCounts.computeIfAbsent(twinProcessId, id -> new AtomicInteger());
        int reserved = counter.incrementAndGet();
        if (reserved > max) {
            counter.decrementAndGet();
            return new GovernanceDecision(false,
                    "Evolution quota exceeded for twin process " + twinProcessId
                            + " (" + max + "/" + max + ")");
        }
        return new GovernanceDecision(true, "Allowed by governance policy");
    }

    // only call this for a slot we actually reserved. denial paths never touched the counter.
    @Override
    public void releaseEvolutionSlot(String twinProcessId) {
        AtomicInteger counter = evolutionCounts.get(twinProcessId);
        if (counter != null) {
            counter.decrementAndGet();
        }
    }

    // Same increment-then-roll-back shape as reserveEvolutionSlot, for the same reason: a check followed by a separate increment lets two threads both read max-1 and both take the last slot. No agent type to deny here - the twin executing its own activity isn't asking the catalog for anything.
    @Override
    public GovernanceDecision reserveTwinExecutionSlot(String twinProcessId) {
        int max = maxTwinExecutionsPerTwin;
        AtomicInteger counter = twinExecutionCounts.computeIfAbsent(twinProcessId, id -> new AtomicInteger());
        int reserved = counter.incrementAndGet();
        if (reserved > max) {
            counter.decrementAndGet();
            return new GovernanceDecision(false,
                    "Twin execution quota exceeded for twin process " + twinProcessId
                            + " (" + max + "/" + max + ")");
        }
        return new GovernanceDecision(true, "Allowed by governance policy");
    }

    @Override
    public void releaseTwinExecutionSlot(String twinProcessId) {
        AtomicInteger counter = twinExecutionCounts.get(twinProcessId);
        if (counter != null) {
            counter.decrementAndGet();
        }
    }

    @Override
    public GovernanceUsage getUsage(String twinProcessId) {
        AtomicInteger evolutions = evolutionCounts.get(twinProcessId);
        AtomicInteger twinExecutions = twinExecutionCounts.get(twinProcessId);
        return new GovernanceUsage(twinProcessId,
                evolutions == null ? 0 : evolutions.get(), maxEvolutionsPerTwin,
                twinExecutions == null ? 0 : twinExecutions.get(), maxTwinExecutionsPerTwin);
    }
}
