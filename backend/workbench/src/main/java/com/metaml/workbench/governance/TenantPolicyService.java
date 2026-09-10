package com.metaml.workbench.governance;

import jakarta.annotation.PostConstruct;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

// Provides tenant-scoped policy persistence and version management.
@Component
public class TenantPolicyService {

    private final Map<String, Tenant> tenants = new ConcurrentHashMap<>();
    private final Map<String, Policy> policies = new ConcurrentHashMap<>();
    private final Map<String, List<PolicyVersion>> versionsByPolicyId = new ConcurrentHashMap<>();
    private final TenantPolicyStore store;
    // Synchronizes policy modifications across concurrent requests.
    private final Object writeLock = new Object();

    public TenantPolicyService(TenantPolicyStore store) {
        this.store = store;
    }

    @PostConstruct
    void restore() {
        TenantPolicyStore.Snapshot snapshot = store.load();
        for (Tenant tenant : snapshot.tenants()) {
            tenants.put(tenant.id(), tenant);
        }
        for (Policy policy : snapshot.policies()) {
            policies.put(policy.id(), policy);
        }
        for (Map.Entry<String, List<PolicyVersion>> entry : snapshot.versionsByPolicyId().entrySet()) {
            versionsByPolicyId.put(entry.getKey(), new CopyOnWriteArrayList<>(entry.getValue()));
        }
    }

    public Tenant createTenant(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Tenant name must not be blank");
        }
        Tenant tenant = new Tenant(UUID.randomUUID().toString(), name.trim(), Instant.now());
        tenants.put(tenant.id(), tenant);
        persist();
        return tenant;
    }

    public List<Tenant> listTenants() {
        return new ArrayList<>(tenants.values());
    }

    public Tenant getTenant(String tenantId) {
        Tenant tenant = tenants.get(tenantId);
        if (tenant == null) {
            throw new NoSuchElementException("Tenant not found: " + tenantId);
        }
        return tenant;
    }

    // Creates a tenant-scoped policy after validating tenant existence.
    public Policy createTenantPolicy(String tenantId, String name) {
        getTenant(tenantId);
        return createPolicy(tenantId, name);
    }

    public Policy createPlatformPolicy(String name) {
        return createPolicy(null, name);
    }

    private Policy createPolicy(String tenantId, String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Policy name must not be blank");
        }
        Policy policy = new Policy(UUID.randomUUID().toString(), tenantId, name.trim(), Instant.now());
        policies.put(policy.id(), policy);
        versionsByPolicyId.put(policy.id(), new CopyOnWriteArrayList<>());
        persist();
        return policy;
    }

    // Lists tenant-scoped policies for a given tenant ID.
    public List<Policy> listTenantPolicies(String tenantId) {
        getTenant(tenantId);
        return policies.values().stream()
                .filter(p -> tenantId.equals(p.tenantId()))
                .toList();
    }

    public List<Policy> listPlatformPolicies() {
        return policies.values().stream().filter(Policy::isPlatform).toList();
    }

    public PolicyVersion createDraftVersion(String policyId, String tenantId) {
        synchronized (writeLock) {
            requirePolicyAccessible(policyId, tenantId);
            List<PolicyVersion> versions = versionsByPolicyId.computeIfAbsent(policyId,
                    id -> new CopyOnWriteArrayList<>());
            int nextNumber = versions.stream().mapToInt(PolicyVersion::versionNumber).max().orElse(0) + 1;
            PolicyVersion version = new PolicyVersion(UUID.randomUUID().toString(), policyId, nextNumber,
                    PolicyVersionStatus.DRAFT, new ArrayList<>(), Instant.now(), null);
            versions.add(version);
            persist();
            return version;
        }
    }

    public PolicyVersion addRule(String versionId, String tenantId, String field, String operator, String value,
            PolicyEffect effect) {
        if (field == null || field.isBlank() || operator == null || operator.isBlank() || effect == null) {
            throw new IllegalArgumentException("field, operator and effect must not be blank");
        }
        synchronized (writeLock) {
            PolicyVersion version = requireVersion(versionId);
            requirePolicyAccessible(version.policyId(), tenantId);
            if (version.status() != PolicyVersionStatus.DRAFT) {
                throw new IllegalStateException("Cannot add a rule to version " + version.versionNumber()
                        + " of policy " + version.policyId() + " - it is " + version.status()
                        + ", not DRAFT. Create a new draft version instead.");
            }
            PolicyRule rule = new PolicyRule(UUID.randomUUID().toString(), field, operator, value, effect);
            List<PolicyRule> newRules = new ArrayList<>(version.rules());
            newRules.add(rule);
            PolicyVersion updated = new PolicyVersion(version.id(), version.policyId(), version.versionNumber(),
                    version.status(), newRules, version.createdAt(), version.activatedAt());
            replaceVersion(updated);
            persist();
            return updated;
        }
    }

    public PolicyVersion activateVersion(String versionId, String tenantId) {
        synchronized (writeLock) {
            PolicyVersion version = requireVersion(versionId);
            requirePolicyAccessible(version.policyId(), tenantId);
            if (version.status() != PolicyVersionStatus.DRAFT) {
                // Reject activation if version is not DRAFT.
                throw new IllegalStateException("Cannot activate version " + version.versionNumber()
                        + " of policy " + version.policyId() + " - it is " + version.status() + ", not DRAFT");
            }
            List<PolicyVersion> versions = versionsByPolicyId.get(version.policyId());
            List<PolicyVersion> updated = new ArrayList<>();
            for (PolicyVersion v : versions) {
                if (v.id().equals(versionId)) {
                    updated.add(new PolicyVersion(v.id(), v.policyId(), v.versionNumber(),
                            PolicyVersionStatus.ACTIVE, v.rules(), v.createdAt(), Instant.now()));
                } else if (v.status() == PolicyVersionStatus.ACTIVE) {
                    // Retire previous active version.
                    updated.add(new PolicyVersion(v.id(), v.policyId(), v.versionNumber(),
                            PolicyVersionStatus.RETIRED, v.rules(), v.createdAt(), v.activatedAt()));
                } else {
                    updated.add(v);
                }
            }
            versionsByPolicyId.put(version.policyId(), new CopyOnWriteArrayList<>(updated));
            persist();
            return requireVersion(versionId);
        }
    }

    public List<PolicyVersion> listVersions(String policyId, String tenantId) {
        requirePolicyAccessible(policyId, tenantId);
        return new ArrayList<>(versionsByPolicyId.getOrDefault(policyId, List.of()));
    }

    // Resolves active policy version for a policy ID.
    public Optional<PolicyVersion> getActiveVersion(String policyId) {
        return versionsByPolicyId.getOrDefault(policyId, List.of()).stream()
                .filter(v -> v.status() == PolicyVersionStatus.ACTIVE)
                .findFirst();
    }

    private void requirePolicyAccessible(String policyId, String tenantId) {
        Policy policy = policies.get(policyId);
        boolean ownedByCaller = policy != null && Objects.equals(policy.tenantId(), tenantId);
        // Validates policy exists and belongs to tenant.
        if (policy == null || !ownedByCaller) {
            throw new NoSuchElementException("Policy not found: " + policyId);
        }
    }

    private PolicyVersion requireVersion(String versionId) {
        for (List<PolicyVersion> versions : versionsByPolicyId.values()) {
            for (PolicyVersion v : versions) {
                if (v.id().equals(versionId)) {
                    return v;
                }
            }
        }
        throw new NoSuchElementException("Policy version not found: " + versionId);
    }

    private void replaceVersion(PolicyVersion updated) {
        List<PolicyVersion> versions = versionsByPolicyId.get(updated.policyId());
        for (int i = 0; i < versions.size(); i++) {
            if (versions.get(i).id().equals(updated.id())) {
                versions.set(i, updated);
                return;
            }
        }
    }

    private void persist() {
        store.save(new TenantPolicyStore.Snapshot(new ArrayList<>(tenants.values()),
                new ArrayList<>(policies.values()), new HashMap<>(versionsByPolicyId)));
    }
}
