package com.metaml.wbapi.payload.response;

import com.metaml.workbench.capability.CapabilityContract;
import com.metaml.workbench.capability.IoDeclaration;
import com.metaml.workbench.capability.runtime.CapabilityBinding;

import java.util.ArrayList;
import java.util.List;

// The wire shape GET /transmute/bindings actually serves (P7 Step 5) - deliberately NOT the domain
// CapabilityBinding record serialized directly. Explicit fields (an epoch-millis Long rather than an
// Instant, a flat producedOutputs list rather than the full CapabilityContract) avoid depending on
// whichever JSR-310/record-deserialization configuration happens to be registered on either side of
// this HTTP boundary - the same reasoning CapabilityBindingStore's own persisted DTO shape already
// follows for the same type, on disk instead of over HTTP.
//
// Only capabilityId and producedOutputs(name/type) are carried for the contract: those are the only
// CapabilityContract fields CapabilityOutputPropagator.publish() actually reads (requiredInputs,
// executionMode, governanceLabels and constraints govern matching/orchestration concerns a Target
// Platform executing an already-approved binding has no use for). The Target Platform reconstructs a
// full CapabilityContract with safe, inert defaults for the fields not carried on the wire.
public record CapabilityBindingResponse(String processDefinitionKey, String activityId, String providerId,
        String providerType, String version, String capabilityId, List<IoDeclarationResponse> producedOutputs,
        String approvalId, Long boundAtEpochMillis) {

    public record IoDeclarationResponse(String name, String type, boolean required) {
    }

    public static CapabilityBindingResponse from(CapabilityBinding binding) {
        CapabilityContract contract = binding.contract();
        List<IoDeclarationResponse> outputs = new ArrayList<>();
        for (IoDeclaration declaration : contract.producedOutputs()) {
            outputs.add(new IoDeclarationResponse(declaration.name(), declaration.type().name(),
                    declaration.required()));
        }
        return new CapabilityBindingResponse(binding.processDefinitionKey(), binding.activityId(),
                binding.providerId(), binding.providerType(), binding.version(), contract.capabilityId(), outputs,
                binding.approvalId(), binding.boundAt() == null ? null : binding.boundAt().toEpochMilli());
    }
}
