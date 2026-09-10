package com.metaml.workbench.capability.runtime;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

// The Target Platform side of P7 Step 5: a per-application, activity-id-keyed cache of
// Workbench-authoritative CapabilityBinding records, so ordinary provider execution does not require
// a live Workbench request once a binding has been resolved.
//
// Deliberately asymmetric, and deliberately so:
// - A resolved (positive) binding is STICKY: once cached, it is trusted for the life of this cache
//   and never re-validated against the Workbench again. This is what makes "no live Workbench request
//   after a valid binding is cached" true, and it is honest about its own limit - a rebind on the
//   Workbench after this entry was cached is not observed until this process restarts. The record
//   itself carries its own bind-time provenance (see CapabilityBinding), so it never claims to be
//   more current than it is.
// - A miss is NEVER cached. An activity legitimately bound after this application started must still
//   become resolvable the next time it executes, without restarting anything, so every miss retries
//   resolver.resolve(...) rather than remembering "nothing was here last time".
//
// No default construction with resolver == null and no bindings seeded: a Target Platform that never
// wires a resolver, or one whose boot-time fetch found nothing, still works - get() simply keeps
// returning empty, which CapabilityDispatcher already treats as "nothing bound" and its caller (the
// generated worker) already knows how to turn into either harmless fallback or an explicit
// CapabilityBindingRequiredException. This class fabricates nothing and decides nothing about which
// of those two an empty result means.
public final class CapabilityBindingCache {

    private final Map<String, CapabilityBinding> resolved = new ConcurrentHashMap<>();
    private final CapabilityBindingResolver resolver;

    // resolver may be null: a cache with no resolver only ever serves what was seeded, and returns
    // empty for everything else - a legitimate, if minimal, deployment (e.g. Workbench connectivity
    // deliberately disabled).
    public CapabilityBindingCache(CapabilityBindingResolver resolver) {
        this.resolver = resolver;
    }

    // Bulk-populates the cache from a one-time boot-time fetch. Bindings not already present are
    // added; an activity already resolved (e.g. by an earlier miss-triggered fetch racing this call)
    // keeps its existing entry rather than being overwritten, since both are equally authoritative and
    // neither is stale by construction - CapabilityBinding is never mutated in place.
    public void seed(Collection<CapabilityBinding> bindings) {
        for (CapabilityBinding binding : bindings) {
            resolved.putIfAbsent(binding.activityId(), binding);
        }
    }

    // The current binding for this activity, or empty when none is cached and none could be resolved
    // (including when this cache has no resolver at all). Never throws on a resolver failure - a
    // resolver that cannot reach the Workbench is exactly the RETRIEVAL_FAILURE case, and this class
    // reports that identically to "nothing bound" rather than fabricating a distinct outcome; the
    // caller's own required-vs-not-required decision already covers both correctly.
    public Optional<CapabilityBinding> get(String activityId) {
        CapabilityBinding cached = resolved.get(activityId);
        if (cached != null) {
            return Optional.of(cached);
        }
        if (resolver == null) {
            return Optional.empty();
        }
        Optional<CapabilityBinding> fetched;
        try {
            fetched = resolver.resolve(activityId);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
        fetched.ifPresent(binding -> resolved.put(activityId, binding));
        return fetched;
    }
}
