package com.metaml.workbench.capability;

import java.util.Objects;
import java.util.regex.Pattern;

// A single named input or output on a CapabilityContract. Immutable and Jackson-serializable.
//
// There is deliberately no defaultValue field: an IoDeclaration describes the *shape* of an input or
// output, never a business value. required==false means the declaration does not participate in
// CapabilitySatisfaction - it is neither demanded of a provider nor required to be available.
public record IoDeclaration(String name, IoType type, boolean required) {

    // Same rule enforced today in NodeManagerClient.SAFE_OUTPUT_NAME and
    // AgentOutputDeclarations.SAFE_VARIABLE_NAME: names land as Camunda process variables, so plain
    // camelCase only.
    private static final Pattern SAFE_NAME = Pattern.compile("^[A-Za-z][A-Za-z0-9]*$");

    public IoDeclaration {
        Objects.requireNonNull(name, "name must not be null");
        if (!SAFE_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException(
                    "IoDeclaration name '" + name + "' must match " + SAFE_NAME.pattern());
        }
        Objects.requireNonNull(type, "type must not be null");
    }
}
