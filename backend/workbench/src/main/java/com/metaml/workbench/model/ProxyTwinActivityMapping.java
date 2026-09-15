package com.metaml.workbench.model;

import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Authored-model intent that identifies one proxy activity, its twin counterpart, and the
 * stable synchronization identity the target-platform generator must use for that pair.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Embeddable
public class ProxyTwinActivityMapping {
    private String proxyActivityId;
    private String twinActivityId;
    private String synchronizationKey;
}
