package com.tp.TargetPlatform.capability;

/** A genuine provider-side technical failure, intentionally distinct from a business result. */
public class ProviderTechnicalFailureException extends RuntimeException {

    public ProviderTechnicalFailureException(String providerIdentity) {
        super("Provider '" + providerIdentity + "' is in TECHNICAL_FAILURE mode");
    }
}
