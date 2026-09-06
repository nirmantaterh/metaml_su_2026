package com.metaml.wbapi;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Properties;

import org.junit.jupiter.api.Test;

// Pins the ACTUAL configured value in application.properties, not a
// re-typed copy of it - a hand-copied literal here would keep passing even if someone edited the
// real property back to a path under the repo, which defeats the point of a regression test.
//
// Deliberately no Spring context: this only needs to read one property and resolve one path the
// same way the JVM's working directory does, and every other test in this module that boots a
// full context already isolates itself onto a test-local output directory via
// @IsolatedWorkbenchTest - a full boot here would just add startup cost for no extra coverage.
class GeneratedTargetPlatformOutputDirectoryConfigTest {

    @Test
    void configuredOutputDirectoryIsNotADescendantOfTheWorkbenchRepository() throws IOException {
        String configuredValue = readProperty("workbench.generation.output-directory");
        assertThat(configuredValue).as("workbench.generation.output-directory must be set explicitly - "
                + "leaving it on the library default would silently write back under the repo").isNotBlank();

        // Same resolution the JVM applies at runtime: relative to the process working directory,
        // which for this module (both `spring-boot:run` and Surefire) is backend/wbapi.
        Path resolved = Path.of(configuredValue).toAbsolutePath().normalize();
        Path repoRoot = findRepoRoot(Path.of("").toAbsolutePath());

        assertThat(resolved.startsWith(repoRoot))
                .as("configured output directory %s must not be a descendant of the workbench repository root %s",
                        resolved, repoRoot)
                .isFalse();
    }

    private static String readProperty(String key) throws IOException {
        Properties properties = new Properties();
        try (InputStream in = GeneratedTargetPlatformOutputDirectoryConfigTest.class
                .getResourceAsStream("/application.properties")) {
            assertThat(in).as("application.properties must be on the test classpath").isNotNull();
            properties.load(in);
        }
        return properties.getProperty(key);
    }

    // Walks up from the module's own working directory to the checked-out repository root,
    // identified by its .git directory - robust to however deep the calling module sits, unlike
    // a fixed number of getParent() hops.
    private static Path findRepoRoot(Path start) {
        for (Path candidate = start; candidate != null; candidate = candidate.getParent()) {
            if (java.nio.file.Files.isDirectory(candidate.resolve(".git"))) {
                return candidate;
            }
        }
        throw new IllegalStateException("Could not find a .git directory above " + start
                + " - is this test running inside the checked-out repository?");
    }
}
