package com.tp.TargetPlatform.capability;

import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.springframework.stereotype.Component;

/**
 * In-memory, operator-controlled technical mode keyed by the provider identity that a binding
 * resolved. It deliberately has no Workbench, governance, or binding side effects.
 */
@Component
public class ProviderTechnicalModeRegistry {

    private final ConcurrentMap<String, ProviderTechnicalMode> modes = new ConcurrentHashMap<>();

    public ProviderTechnicalMode modeOf(String providerIdentity) {
        return modes.getOrDefault(key(providerIdentity), ProviderTechnicalMode.NORMAL);
    }

    public ProviderTechnicalMode setMode(String providerIdentity, ProviderTechnicalMode mode) {
        Objects.requireNonNull(mode, "mode must not be null");
        String key = key(providerIdentity);
        if (mode == ProviderTechnicalMode.NORMAL) {
            modes.remove(key);
        } else {
            modes.put(key, mode);
        }
        return mode;
    }

    private static String key(String providerIdentity) {
        if (providerIdentity == null || providerIdentity.isBlank()) {
            throw new IllegalArgumentException("Provider identity must not be blank");
        }
        return providerIdentity.trim().toLowerCase(Locale.ROOT);
    }
}
