package com.metaml.workbench.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class WorkbenchServiceTargetPlatformUrlTest {

    @Test
    void preservesAnExplicitExternalTargetPlatformUrlIncludingItsPath() {
        assertThat(WorkbenchServiceImpl.resolveTargetPlatformUrl("https://manufacturing.example.com/metaml", 64759))
                .isEqualTo("https://manufacturing.example.com/metaml");
    }

    @Test
    void usesTheActualLocalLauncherPortWhenNoExternalUrlIsConfigured() {
        assertThat(WorkbenchServiceImpl.resolveTargetPlatformUrl("", 64759))
                .isEqualTo("http://127.0.0.1:64759");
    }
}
