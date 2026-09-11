package com.metaml.workbench.capability.runtime;

import java.util.Optional;

// The seam CapabilityBindingCache uses to reach outside this JVM for a binding it does not already
// hold. Deliberately just a function: this module carries no Spring, no HTTP client and no knowledge
// of the Workbench's transport (see this module's own description) - a generated Target Platform
// supplies the real implementation (an HTTP call against the Workbench's binding endpoint) from its
// own template code, exactly as it already supplies ComponentExecutor implementations.
//
// A failed or unreachable resolution is Optional.empty(), the same as "nothing bound" - this seam
// carries no distinct retrieval-failure signal of its own. CapabilityBindingCache is what decides,
// from this alone, whether that means the activity has nothing to bind or a live capability gap; see
// CapabilityBindingCache's own documentation.
public interface CapabilityBindingResolver {

    Optional<CapabilityBinding> resolve(String activityId);
}
