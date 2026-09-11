package com.metaml.workbench.capability.gap;

// Where a CapabilityGap was detected (MetaML Scope 6, Phase 5).
//
// STATIC_MODEL: derived from the process model itself, before any process instance exists, via
// BpmnCapabilityContractReader (Phase 1). No process instance, no activity instance, no runtime
// inputs - purely a model-level fact.
//
// RUNTIME_WORKBENCH_TWIN: detected while the workbench's own twin process instance is executing an
// activity visit and no provider in the current catalog snapshot can satisfy it.
//
// RUNTIME_WORKBENCH_ORIGINAL: detected from the original (non-twin) process instance's runtime
// state - e.g. a gateway or activity on the original process that the twin has not yet mirrored.
//
// RUNTIME_TARGET_PLATFORM: reported by a generated Target Platform's own running process instance,
// over the Workbench HTTP boundary (Phase 5, section 19). Workbench remains authoritative; the
// Target Platform never runs its own governance, approval, evolution, or catalog.
public enum GapOrigin {
    STATIC_MODEL,
    RUNTIME_WORKBENCH_TWIN,
    RUNTIME_WORKBENCH_ORIGINAL,
    RUNTIME_TARGET_PLATFORM
}
