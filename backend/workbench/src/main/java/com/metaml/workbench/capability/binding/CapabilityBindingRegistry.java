package com.metaml.workbench.capability.binding;

import com.metaml.workbench.capability.runtime.CapabilityBinding;

import jakarta.annotation.PostConstruct;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

// The Workbench's in-memory index over its current, durable CapabilityBinding state (P7 Step 5): one
// current provider per (processDefinitionKey, activityId), fed by every evolution path that succeeds -
// ordinary /transmute/evolve and capability-gap bind() alike, since both converge on
// WorkbenchServiceImpl.executeAfterGovernance, which is the only caller of upsert() below.
//
// Not itself the authority on WHETHER a binding may be made - that stays entirely with
// WorkbenchServiceImpl's existing governance/policy/node-manager checks, which already ran by the
// time upsert() is called. This class only ever records a decision that has already been made.
@Component
public class CapabilityBindingRegistry {

    private static final Logger logger = LoggerFactory.getLogger(CapabilityBindingRegistry.class);

    private final Map<String, CapabilityBinding> bindings = new ConcurrentHashMap<>();
    private final CapabilityBindingStore store;
    private final Object writeLock = new Object();

    public CapabilityBindingRegistry(CapabilityBindingStore store) {
        this.store = store;
    }

    @PostConstruct
    void restore() {
        for (CapabilityBinding binding : store.load()) {
            bindings.put(binding.key(), binding);
        }
        if (!bindings.isEmpty()) {
            logger.info("Restored {} current capability binding(s)", bindings.size());
        }
    }

    // Replaces the current binding for (processDefinitionKey, activityId) with the given one -
    // upsert, never append. Persists BEFORE updating the in-memory index, and only updates the index
    // if persistence succeeds: on CapabilityBindingPersistenceException, the in-memory index is left
    // completely untouched (still reflecting whatever was current before this call), and the
    // exception propagates to the caller - which is required to treat the whole binding operation as
    // failed, never as merely "durability is behind". This ordering is what makes a rebind failure
    // correctly leave the PREVIOUS binding as current rather than losing it: the candidate the
    // persistence attempt failed on is discarded, not committed.
    public void upsert(CapabilityBinding binding) {
        synchronized (writeLock) {
            Map<String, CapabilityBinding> candidate = new ConcurrentHashMap<>(bindings);
            candidate.put(binding.key(), binding);
            store.save(new ArrayList<>(candidate.values()));
            bindings.put(binding.key(), binding);
        }
    }

    public Optional<CapabilityBinding> current(String processDefinitionKey, String activityId) {
        return Optional.ofNullable(bindings.get(CapabilityBinding.key(processDefinitionKey, activityId)));
    }

    public List<CapabilityBinding> all() {
        return List.copyOf(bindings.values());
    }
}
