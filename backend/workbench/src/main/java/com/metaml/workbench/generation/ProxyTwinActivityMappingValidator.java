package com.metaml.workbench.generation;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.camunda.bpm.model.bpmn.Bpmn;
import org.camunda.bpm.model.bpmn.BpmnModelInstance;
import org.camunda.bpm.model.bpmn.instance.Activity;

import com.metaml.workbench.model.ProxyTwinActivityMapping;

/** Validates authored-model correspondence without deriving any correspondence heuristically. */
public final class ProxyTwinActivityMappingValidator {
    private ProxyTwinActivityMappingValidator() { }

    public static void validate(String proxyBpmnXml, String twinBpmnXml,
            List<ProxyTwinActivityMapping> mappings) {
        List<ProxyTwinActivityMapping> effective = mappings == null ? List.of() : mappings;
        if (effective.isEmpty()) return;
        BpmnModelInstance proxy = Bpmn.readModelFromStream(
                new ByteArrayInputStream(proxyBpmnXml.getBytes(StandardCharsets.UTF_8)));
        BpmnModelInstance twin = Bpmn.readModelFromStream(
                new ByteArrayInputStream(twinBpmnXml.getBytes(StandardCharsets.UTF_8)));
        Set<String> proxyIds = new HashSet<>();
        Set<String> twinIds = new HashSet<>();
        Set<String> keys = new HashSet<>();
        Set<String> signalNames = new HashSet<>();
        for (ProxyTwinActivityMapping mapping : effective) {
            if (mapping == null) {
                throw new IllegalArgumentException("Proxy/Twin synchronization mapping must not be null.");
            }
            String proxyId = required(mapping.getProxyActivityId(), "Proxy activity");
            String twinId = required(mapping.getTwinActivityId(), "Twin activity");
            String key = required(mapping.getSynchronizationKey(), "synchronization key");
            if (!(proxy.getModelElementById(proxyId) instanceof Activity)) {
                throw new IllegalArgumentException(
                        "Proxy/Twin synchronization mapping references unknown Proxy activity '" + proxyId + "'.");
            }
            if (!(twin.getModelElementById(twinId) instanceof Activity)) {
                throw new IllegalArgumentException(
                        "Proxy/Twin synchronization mapping references unknown Twin activity '" + twinId + "'.");
            }
            if (!proxyIds.add(proxyId)) {
                throw new IllegalArgumentException("Duplicate Proxy/Twin Proxy activity mapping '" + proxyId + "'.");
            }
            if (!twinIds.add(twinId)) {
                throw new IllegalArgumentException("Duplicate Proxy/Twin Twin activity mapping '" + twinId + "'.");
            }
            if (!keys.add(key)) {
                throw new IllegalArgumentException("Duplicate Proxy/Twin synchronization key '" + key + "'.");
            }
            if (!signalNames.add(signalNameFor(key))) {
                throw new IllegalArgumentException("Duplicate Proxy/Twin synchronization key after normalization '"
                        + key + "'.");
            }
        }
    }

    private static String required(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Proxy/Twin synchronization mapping " + label + " must not be blank.");
        }
        return value;
    }

    public static String signalNameFor(String synchronizationKey) {
        String normalized = synchronizationKey.trim().replaceAll("[^A-Za-z0-9_.-]+", "_");
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("Proxy/Twin synchronization mapping synchronization key must not be blank.");
        }
        return "sync_" + normalized;
    }
}
