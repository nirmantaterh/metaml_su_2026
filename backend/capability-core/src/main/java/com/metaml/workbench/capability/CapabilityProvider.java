package com.metaml.workbench.capability;

import java.util.Objects;
import java.util.regex.Pattern;

// A concrete thing that can satisfy a CapabilityContract. Immutable and Jackson-serializable.
//
// providerId is instance identity (today's agentName) and is what CapabilityProvider identity is
// based on. providerType is family identity (today's agentType); ordering within a providerType is
// semver-descending, per CapabilitySatisfaction.
public record CapabilityProvider(String providerId, String providerType, String version,
        CapabilityContract contract, String description, boolean available, String reason) {

    // Same rule enforced today in NodeManagerClient.SAFE_AGENT_TYPE.
    private static final Pattern SAFE_PROVIDER_TYPE = Pattern.compile("^[a-z0-9-]+$");

    private static final String UNSPECIFIED_VERSION = "0.0.0";

    public CapabilityProvider {
        Objects.requireNonNull(providerId, "providerId must not be null");
        if (providerId.isBlank()) {
            throw new IllegalArgumentException("providerId must not be blank");
        }
        Objects.requireNonNull(providerType, "providerType must not be null");
        if (!SAFE_PROVIDER_TYPE.matcher(providerType).matches()) {
            throw new IllegalArgumentException(
                    "providerType '" + providerType + "' must match " + SAFE_PROVIDER_TYPE.pattern());
        }
        version = (version == null || version.isBlank()) ? UNSPECIFIED_VERSION : version;
        Objects.requireNonNull(contract, "contract must not be null");
    }
}
