package com.metaml.workbench.capability.gap;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.metaml.workbench.capability.CapabilityContract;
import com.metaml.workbench.capability.ExecutionMode;
import com.metaml.workbench.capability.IoDeclaration;
import com.metaml.workbench.capability.IoType;

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

// Persists CapabilityGap state to JSON using atomic write-and-replace (MetaML Scope 6, Phase 5,
// section 10) - the exact same pattern as the existing ApprovalStore: a @Component wrapping a
// Jackson ObjectMapper, a tmp-file-then-atomic-move write, and a DTO inner class so the persisted
// shape is independent of the domain record's own shape. No database is introduced.
//
// The persisted representation carries no business values (section 7): availableInputs is
// name/type pairs only, and candidateProviderIds is provider identity strings only.
@Component
public class CapabilityGapStore {

    private static final Logger logger = LoggerFactory.getLogger(CapabilityGapStore.class);

    private final Path file;
    private final boolean enabled;
    private final ObjectMapper mapper = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private final Object writeLock = new Object();

    public CapabilityGapStore(@Value("${workbench.capability.gap.file:./data/capability-gaps.json}") String path,
            @Value("${workbench.capability.gap.persist:true}") boolean enabled) {
        this.file = Path.of(path);
        this.enabled = enabled;
    }

    public List<CapabilityGap> load() {
        if (!enabled || !Files.isRegularFile(file)) {
            return List.of();
        }
        try {
            CapabilityGapDto[] dtos = mapper.readValue(file.toFile(), CapabilityGapDto[].class);
            List<CapabilityGap> gaps = new ArrayList<>();
            for (CapabilityGapDto dto : dtos) {
                gaps.add(dto.toCapabilityGap());
            }
            logger.info("Restored {} capability gap(s) from {}", gaps.size(), file.toAbsolutePath());
            return gaps;
        } catch (IOException | RuntimeException e) {
            logger.warn("Could not read capability gap state from {}, carrying on with nothing restored: {}",
                    file.toAbsolutePath(), e.toString());
            return List.of();
        }
    }

    public void save(List<CapabilityGap> gaps) {
        if (!enabled) {
            return;
        }
        synchronized (writeLock) {
            List<CapabilityGapDto> dtos = new ArrayList<>();
            for (CapabilityGap gap : gaps) {
                dtos.add(CapabilityGapDto.of(gap));
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
                logger.warn("Could not write capability gap state to {}: {}", file.toAbsolutePath(), e.toString());
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

    static final class CapabilityGapDto {
        public String gapId;
        public String processDefinitionId;
        public String activityId;
        public String activityInstanceId;
        public Integer loopCounter;
        public String processInstanceId;
        public CapabilityContractDto requiredContract;
        public Map<String, String> availableInputs;
        public List<String> candidateProviderIds;
        public String tenantId;
        public String origin;
        public String status;
        public Long detectedAtEpochMillis;
        public Long lastTransitionAtEpochMillis;
        public String approvalId;
        public String boundProviderId;

        static CapabilityGapDto of(CapabilityGap gap) {
            CapabilityGapDto dto = new CapabilityGapDto();
            dto.gapId = gap.gapId();
            dto.processDefinitionId = gap.processDefinitionId();
            dto.activityId = gap.activityId();
            dto.activityInstanceId = gap.activityInstanceId();
            dto.loopCounter = gap.loopCounter();
            dto.processInstanceId = gap.processInstanceId();
            dto.requiredContract = CapabilityContractDto.of(gap.requiredContract());
            Map<String, String> inputs = new LinkedHashMap<>();
            for (Map.Entry<String, IoType> entry : gap.availableInputs().entrySet()) {
                inputs.put(entry.getKey(), entry.getValue().name());
            }
            dto.availableInputs = inputs;
            dto.candidateProviderIds = new ArrayList<>(gap.candidateProviderIds());
            dto.tenantId = gap.tenantId();
            dto.origin = gap.origin().name();
            dto.status = gap.status().name();
            dto.detectedAtEpochMillis = gap.detectedAt() == null ? null : gap.detectedAt().toEpochMilli();
            dto.lastTransitionAtEpochMillis =
                    gap.lastTransitionAt() == null ? null : gap.lastTransitionAt().toEpochMilli();
            dto.approvalId = gap.approvalId();
            dto.boundProviderId = gap.boundProviderId();
            return dto;
        }

        CapabilityGap toCapabilityGap() {
            Map<String, IoType> inputs = new LinkedHashMap<>();
            if (availableInputs != null) {
                for (Map.Entry<String, String> entry : availableInputs.entrySet()) {
                    inputs.put(entry.getKey(), IoType.valueOf(entry.getValue()));
                }
            }
            return new CapabilityGap(gapId, processDefinitionId, activityId, activityInstanceId, loopCounter,
                    processInstanceId, requiredContract.toCapabilityContract(), inputs,
                    candidateProviderIds == null ? List.of() : candidateProviderIds, tenantId,
                    GapOrigin.valueOf(origin), GapStatus.valueOf(status),
                    detectedAtEpochMillis == null ? null : Instant.ofEpochMilli(detectedAtEpochMillis),
                    lastTransitionAtEpochMillis == null ? null : Instant.ofEpochMilli(lastTransitionAtEpochMillis),
                    approvalId, boundProviderId);
        }
    }
}
