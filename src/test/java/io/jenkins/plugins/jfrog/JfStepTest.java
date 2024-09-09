package io.jenkins.plugins.jfrog;

import hudson.EnvVars;
import hudson.FilePath;
import hudson.Launcher;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.stream.Stream;

import static io.jenkins.plugins.jfrog.JfStep.getJFrogCLIPath;
import static io.jenkins.plugins.jfrog.JfrogInstallation.JFROG_BINARY_PATH;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * @author yahavi
 **/
public class JfStepTest {

    @ParameterizedTest
    @MethodSource("jfrogCLIPathProvider")
    void getJFrogCLIPathTest(EnvVars inputEnvVars, boolean isWindows, String expectedOutput) {
        Assertions.assertEquals(expectedOutput, getJFrogCLIPath(inputEnvVars, isWindows));
    }

    @Test
    void isCliVersionGreaterThanTest() {
        JfStep jfStep = new JfStep("--version");

        // Test cases where the current version is greater
        assertTrue(jfStep.isCliVersionGreaterThanOrEqual("2.32.0", "2.31.0"));
        assertTrue(jfStep.isCliVersionGreaterThanOrEqual("3.0.0", "2.31.0"));
        assertTrue(jfStep.isCliVersionGreaterThanOrEqual("2.31.1", "2.31.0"));

        // Test cases where the current version is equal
        assertTrue(jfStep.isCliVersionGreaterThanOrEqual("2.31.0", "2.31.0"));

        // Test cases where the current version is less
        assertFalse(jfStep.isCliVersionGreaterThanOrEqual("2.30.0", "2.31.0"));
        assertFalse(jfStep.isCliVersionGreaterThanOrEqual("2.31.0", "2.31.1"));
        assertFalse(jfStep.isCliVersionGreaterThanOrEqual("1.31.0", "2.31.0"));
    }

    @Test
    void getJfrogCliVersionTest() throws IOException, InterruptedException {
        // Mock the Launcher.ProcStarter
        Launcher.ProcStarter procStarter = mock(Launcher.ProcStarter.class);
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        // Mocks the return value of --version command
        outputStream.write("jf version 2.31.0 ".getBytes());
        // Mock the behavior of the ProcStarter
        when(procStarter.cmds("jf", "--version")).thenReturn(procStarter);
        when(procStarter.pwd((FilePath) any())).thenReturn(procStarter);
        when(procStarter.stdout(any(ByteArrayOutputStream.class))).thenAnswer(invocation -> {
            ByteArrayOutputStream out = invocation.getArgument(0);
            out.write(outputStream.toByteArray());
            return procStarter;
        });
        when(procStarter.join()).thenReturn(0);

        // Create an instance of JfStep and call the method
        JfStep jfStep = new JfStep("--version");
        String version = jfStep.getJfrogCliVersion(procStarter);

        // Verify the result
        assertEquals("2.31.0", version);
    }

    private static Stream<Arguments> jfrogCLIPathProvider() {
        return Stream.of(
                // Unix agent
                Arguments.of(new EnvVars(JFROG_BINARY_PATH, "a/b/c"), false, "a/b/c/jf"),
                Arguments.of(new EnvVars(JFROG_BINARY_PATH, "a\\b\\c"), false, "a/b/c/jf"),
                Arguments.of(new EnvVars(JFROG_BINARY_PATH, ""), false, "jf"),
                Arguments.of(new EnvVars(), false, "jf"),

                // Windows agent
                Arguments.of(new EnvVars(JFROG_BINARY_PATH, "a/b/c"), true, "a\\b\\c\\jf.exe"),
                Arguments.of(new EnvVars(JFROG_BINARY_PATH, "a\\b\\c"), true, "a\\b\\c\\jf.exe"),
                Arguments.of(new EnvVars(JFROG_BINARY_PATH, ""), true, "jf.exe"),
                Arguments.of(new EnvVars(), true, "jf.exe")
        );
    }
}
