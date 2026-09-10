package com.metaml.workbench.generation;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

// Verifies Maven executable resolution across operating systems.
// On Windows, resolveMavenExecutable must prioritize "mvn.cmd" over the POSIX shell script "mvn",
// which Files.isExecutable() otherwise reports as executable on Windows and causes CreateProcess error 193.
class SpringBootProjectLauncherMavenExecutableTest {

    @TempDir
    Path mavenHome;

    private Path bin() {
        return mavenHome.resolve("bin");
    }

    private void writeExecutable(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, "placeholder");
        // best-effort on Windows (no POSIX x-bit there), the real setExecutable(true) that matters
        // on Unix - matches how Files.isExecutable() actually gets satisfied on each platform.
        file.toFile().setExecutable(true);
    }

    @Test
    void windowsPrefersMvnCmdOverTheExtensionlessPosixScriptWhenBothExist() throws IOException {
        // Both files present in the same bin/ - exactly what the real Maven Windows distribution
        // ships - so a naive "check for mvn" would find the wrong one first, same as the real bug.
        writeExecutable(bin().resolve("mvn"));
        writeExecutable(bin().resolve("mvn.cmd"));

        String resolved = SpringBootProjectLauncher.resolveMavenExecutable(mavenHome.toString(), true);

        assertThat(resolved).endsWith("mvn.cmd");
        assertThat(resolved).isEqualTo(bin().resolve("mvn.cmd").toString());
    }

    @Test
    void windowsFallsBackToBareMvnCmdWhenMavenHomeHasNoBinDirectory() {
        // Verifies Windows executable resolution avoids bare 'mvn' when MAVEN_HOME lacks bin/mvn.cmd.
        String resolved = SpringBootProjectLauncher.resolveMavenExecutable(mavenHome.toString(), true);

        assertThat(resolved).isEqualTo("mvn.cmd");
    }

    @Test
    void windowsFallsBackToBareMvnCmdWhenMavenHomeIsNotSet() {
        String resolved = SpringBootProjectLauncher.resolveMavenExecutable(null, true);

        assertThat(resolved).isEqualTo("mvn.cmd");
    }

    @Test
    void nonWindowsStillResolvesTheExtensionlessMvnScript() throws IOException {
        // Existing macOS/Linux behavior must be unchanged: no ".cmd" anywhere in the picture.
        writeExecutable(bin().resolve("mvn"));

        String resolved = SpringBootProjectLauncher.resolveMavenExecutable(mavenHome.toString(), false);

        assertThat(resolved).isEqualTo(bin().resolve("mvn").toString());
        assertThat(resolved).doesNotContain(".cmd");
    }

    @Test
    void nonWindowsFallsBackToBareMvnWhenMavenHomeIsNotSet() {
        String resolved = SpringBootProjectLauncher.resolveMavenExecutable(null, false);

        assertThat(resolved).isEqualTo("mvn");
    }
}
