package com.metaml.workbench.generation;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.metaml.workbench.model.ProxyTwinActivityMapping;

class ProxyTwinActivityMappingValidatorTest {
    private static final String PROXY = bpmn("proxy_task");
    private static final String TWIN = bpmn("twin_task");

    @Test
    void rejectsUnknownActivityReferences() {
        assertThatThrownBy(() -> ProxyTwinActivityMappingValidator.validate(PROXY, TWIN,
                List.of(new ProxyTwinActivityMapping("missing", "twin_task", "key"))))
                .hasMessageContaining("unknown Proxy activity 'missing'");
        assertThatThrownBy(() -> ProxyTwinActivityMappingValidator.validate(PROXY, TWIN,
                List.of(new ProxyTwinActivityMapping("proxy_task", "missing", "key"))))
                .hasMessageContaining("unknown Twin activity 'missing'");
    }

    @Test
    void rejectsNonBijectiveAndDuplicateKeyMappings() {
        assertThatThrownBy(() -> ProxyTwinActivityMappingValidator.validate(PROXY, twinWith("twin_task", "twin_two"),
                List.of(new ProxyTwinActivityMapping("proxy_task", "twin_task", "one"),
                        new ProxyTwinActivityMapping("proxy_task", "twin_two", "two"))))
                .hasMessageContaining("Duplicate Proxy/Twin Proxy activity mapping");
        assertThatThrownBy(() -> ProxyTwinActivityMappingValidator.validate(bpmnWith("proxy_task", "proxy_two"), TWIN,
                List.of(new ProxyTwinActivityMapping("proxy_task", "twin_task", "one"),
                        new ProxyTwinActivityMapping("proxy_two", "twin_task", "two"))))
                .hasMessageContaining("Duplicate Proxy/Twin Twin activity mapping");
        assertThatThrownBy(() -> ProxyTwinActivityMappingValidator.validate(bpmnWith("proxy_task", "proxy_two"),
                twinWith("twin_task", "twin_two"),
                List.of(new ProxyTwinActivityMapping("proxy_task", "twin_task", "same"),
                        new ProxyTwinActivityMapping("proxy_two", "twin_two", "same"))))
                .hasMessageContaining("Duplicate Proxy/Twin synchronization key");
    }

    private static String twinWith(String... ids) { return bpmnWith(ids); }
    private static String bpmnWith(String... ids) {
        return "<bpmn:definitions xmlns:bpmn=\"http://www.omg.org/spec/BPMN/20100524/MODEL\" targetNamespace=\"test\"><bpmn:process id=\"p\">"
                + java.util.Arrays.stream(ids).map(id -> "<bpmn:serviceTask id=\"" + id + "\"/>")
                        .collect(java.util.stream.Collectors.joining()) + "</bpmn:process></bpmn:definitions>";
    }
    private static String bpmn(String id) { return bpmnWith(id); }
}
