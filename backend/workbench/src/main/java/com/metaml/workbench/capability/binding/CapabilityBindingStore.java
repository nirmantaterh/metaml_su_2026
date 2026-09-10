package com.metaml.workbench.capability.binding;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.metaml.workbench.capability.CapabilityContract;
import com.metaml.workbench.capability.ExecutionMode;
import com.metaml.workbench.capability.IoDeclaration;
import com.metaml.workbench.capability.IoType;
import com.metaml.workbench.capability.runtime.CapabilityBinding;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

// Persists the Workbench's current, model-level CapabilityBinding state to JSON using atomic
// write-and-replace (P7 Step 5) - the same shape CapabilityGapStore already established: a
// @Component wrapping a Jackson ObjectMapper, a tmp-file-then-atomic-move write, and DTO inner
// classes so the persisted shape is independent of the domain record's own shape.
//
// Deliberately NOT the same failure behaviour as CapabilityGapStore, and that divergence is
// intentional: save() here THROWS on failure rather than logging and continuing. See
// CapabilityBindingPersistenceException for why. load() stays lenient (best-effort, logs and
// degrades to empty) - a corrupted or unreadable file at startup is a data-recovery question, not the
// "did the operation I just performed actually succeed" question save() exists to answer honestly.
//
// save() always writes the WHOLE current collection, never an increment: this is a current-state
// store, not an append-only history, so there is never more than one row per
// CapabilityBinding.key() on disk.
@Component
public class CapabilityBindingStore {

    private static final Logger logger = LoggerFactory.getLogger(CapabilityBindingStore.class);

    private final Path file;
    private final boolean enabled;
    private final ObjectMapper mapper = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private final Object writeLock = new Object();

    public CapabilityBindingStore(
            @Value("${workbench.capability.binding.file:./data/capability-bindings.json}") String path,
            @Value("${workbench.capability.binding.persist:true}") boolean enabled) {
        this.file = Path.of(path);
        this.enabled = enabled;
    }

    public List<CapabilityBinding> load() {
        if (!enabled || !Files.isRegularFile(file)) {
            return List.of();
        }
        try {
            CapabilityBindingDto[] dtos = mapper.readValue(file.toFile(), CapabilityBindingDto[].class);
            List<CapabilityBinding> bindings = new ArrayList<>();
            for (CapabilityBindingDto dto : dtos) {
                bindings.add(dto.toCapabilityBinding());
            }
            logger.info("Restored {} capability binding(s) from {}", bindings.size(), file.toAbsolutePath());
            return bindings;
        } catch (IOException | RuntimeException e) {
            logger.warn("Could not read capability binding state from {}, carrying on with nothing "
                    + "restored: {}", file.toAbsolutePath(), e.toString());
            return List.of();
        }
    }

    // Throws CapabilityBindingPersistenceException on any failure to write - the caller
    // (CapabilityBindingRegistry.upsert) is required to treat that as the whole binding operation
    // having failed, not as a background inconvenience. When persistence is disabled
    // (workbench.capability.binding.persist=false) this is a deliberate no-op that never throws: an
    // operator who disabled durability explicitly accepted in-memory-only binding state, which is a
    // different decision from a write silently failing.
    public void save(List<CapabilityBinding> bindings) {
        if (!enabled) {
            return;
        }
        synchronized (writeLock) {
            List<CapabilityBindingDto> dtos = new ArrayList<>();
            for (CapabilityBinding binding : bindings) {
                dtos.add(CapabilityBindingDto.of(binding));
            }
            try {
                Path parent = file.toAbsolutePath().getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
                mapper.writeValue(tmp.toFile(), dtos);
                try {
                    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException | RuntimeException e) {
                throw new CapabilityBindingPersistenceException(
                        "Could not durably persist capability binding state to " + file.toAbsolutePath(), e);
            }
        }
    }

    static final class IoDeclarationDto {
        public String name;
        public String type;
        public boolean required;

        static IoDeclarationDto of(IoDeclaration declaration) {
            IoDeclarationDto dto = new IoDeclarationDto();
            dto.name = declaration.name();
            dto.type = declaration.type().name();
            dto.required = declaration.required();
            return dto;
        }

        IoDeclaration toIoDeclaration() {
            return new IoDeclaration(name, IoType.valueOf(type), required);
        }
    }

    static final class CapabilityContractDto {
        public String capabilityId;
        public List<IoDeclarationDto> requiredInputs;
        public List<IoDeclarationDto> producedOutputs;
        public String executionMode;
        public List<String> governanceLabels;
        public Map<String, Object> constraints;

        static CapabilityContractDto of(CapabilityContract contract) {
            CapabilityContractDto dto = new CapabilityContractDto();
            dto.capabilityId = contract.capabilityId();
            dto.requiredInputs = toDtoList(contract.requiredInputs());
            dto.producedOutputs = toDtoList(contract.producedOutputs());
            dto.executionMode = contract.executionMode().name();
            dto.governanceLabels = new ArrayList<>(contract.governanceLabels());
            dto.constraints = new LinkedHashMap<>(contract.constraints());
            return dto;
        }

        private static List<IoDeclarationDto> toDtoList(Set<IoDeclaration> declarations) {
            List<IoDeclarationDto> list = new ArrayList<>();
            for (IoDeclaration declaration : declarations) {
                list.add(IoDeclarationDto.of(declaration));
            }
            return list;
        }

        CapabilityContract toCapabilityContract() {
            Set<IoDeclaration> inputs = new LinkedHashSet<>();
            if (requiredInputs != null) {
                for (IoDeclarationDto dto : requiredInputs) {
                    inputs.add(dto.toIoDeclaration());
                }
            }
            Set<IoDeclaration> outputs = new LinkedHashSet<>();
            if (producedOutputs != null) {
                for (IoDeclarationDto dto : producedOutputs) {
                    outputs.add(dto.toIoDeclaration());
                }
            }
            Set<String> labels = governanceLabels == null ? Set.of() : Set.copyOf(governanceLabels);
            Map<String, Object> constraintsMap = constraints == null ? Map.of() : constraints;
            return new CapabilityContract(capabilityId, inputs, outputs,
                    ExecutionMode.valueOf(executionMode), constraintsMap, labels);
        }
    }

    static final class CapabilityBindingDto {
        public String processDefinitionKey;
        public String activityId;
        public String providerId;
        public String providerType;
        public String version;
        public CapabilityContractDto contract;
        public String approvalId;
        public Long boundAtEpochMillis;

        static CapabilityBindingDto of(CapabilityBinding binding) {
            CapabilityBindingDto dto = new CapabilityBindingDto();
            dto.processDefinitionKey = binding.processDefinitionKey();
            dto.activityId = binding.activityId();
            dto.providerId = binding.providerId();
            dto.providerType = binding.providerType();
            dto.version = binding.version();
            dto.contract = CapabilityContractDto.of(binding.contract());
            dto.approvalId = binding.approvalId();
            dto.boundAtEpochMillis = binding.boundAt() == null ? null : binding.boundAt().toEpochMilli();
            return dto;
        }

        CapabilityBinding toCapabilityBinding() {
            return new CapabilityBinding(processDefinitionKey, activityId, providerId, providerType, version,
                    contract.toCapabilityContract(), approvalId,
                    boundAtEpochMillis == null ? Instant.EPOCH : Instant.ofEpochMilli(boundAtEpochMillis));
        }
    }
}
