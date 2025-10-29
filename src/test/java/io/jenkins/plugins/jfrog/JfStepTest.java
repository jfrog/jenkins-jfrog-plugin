package io.jenkins.plugins.jfrog;

import hudson.EnvVars;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.Job;
import hudson.util.ArgumentListBuilder;
import io.jenkins.plugins.jfrog.configuration.CredentialsConfig;
import io.jenkins.plugins.jfrog.configuration.JFrogPlatformInstance;
import org.jfrog.build.client.Version;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.stream.Stream;

import static io.jenkins.plugins.jfrog.JfStep.Execution.getJFrogCLIPath;
import static io.jenkins.plugins.jfrog.JfStep.MIN_CLI_VERSION_PASSWORD_STDIN;
import static io.jenkins.plugins.jfrog.JfrogInstallation.JFROG_BINARY_PATH;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

/**
 * @author yahavi
 **/
class JfStepTest {

    @ParameterizedTest
    @MethodSource("jfrogCLIPathProvider")
    void getJFrogCLIPathTest(EnvVars inputEnvVars, boolean isWindows, String expectedOutput) {
        assertEquals(expectedOutput, getJFrogCLIPath(inputEnvVars, isWindows));
    }

    static Stream<Arguments> jfrogCLIPathProvider() {
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

    @Test
    void getJfrogCliVersionTest() throws IOException, InterruptedException {
        // Mock the Launcher
        Launcher launcher = mock(Launcher.class);
        // Mock the Launcher.ProcStarter
        Launcher.ProcStarter procStarter = mock(Launcher.ProcStarter.class);
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        // Mocks the return value of --version command
        outputStream.write("jf version 2.31.0 ".getBytes());
        // Mock the behavior of the Launcher and ProcStarter
        when(launcher.launch()).thenReturn(procStarter);
        when(procStarter.cmds(any(ArgumentListBuilder.class))).thenReturn(procStarter);
        when(procStarter.pwd((FilePath) any())).thenReturn(procStarter);
        when(procStarter.stdout(any(ByteArrayOutputStream.class))).thenAnswer(invocation -> {
            ByteArrayOutputStream out = invocation.getArgument(0);
            out.write(outputStream.toByteArray());
            return procStarter;
        });
        when(procStarter.join()).thenReturn(0);

        // Create an instance of JfStep and call the method
        String jfrogBinaryPath = "path/to/jfrog";
        Version version = JfStep.getJfrogCliVersion(procStarter, jfrogBinaryPath);

        // Verify the result
        assertEquals("2.31.0", version.toString());
    }

    /**
     * Tests the addCredentialsArguments method logic with password-stdin vs.-- password flag.
     * Password-stdin flag should only be set if the CLI version is supported
     * AND the launcher is not the plugin launcher.
     * Plugin launchers do not support password-stdin, as they do not have access to the standard input by default.
     *
     * @param cliVersion       The CLI version
     * @param envVars          The env vars
     * @param expectedOutput   The expected output
     */
    @ParameterizedTest
    @MethodSource("provideTestArguments")
    void testAddCredentialsArguments(String cliVersion, EnvVars envVars, String expectedOutput) {
        // Mock the necessary objects
        JFrogPlatformInstance jfrogPlatformInstance = mock(JFrogPlatformInstance.class);
        CredentialsConfig credentialsConfig = mock(CredentialsConfig.class);
        when(jfrogPlatformInstance.getId()).thenReturn("instance-id");
        when(jfrogPlatformInstance.getCredentialsConfig()).thenReturn(credentialsConfig);
        when(credentialsConfig.getCredentialsId()).thenReturn("credentials-id");

        Job<?, ?> job = mock(Job.class);
        Launcher.ProcStarter launcher = mock(Launcher.ProcStarter.class);

        // Determine if password stdin is supported
        boolean passwordStdinSupported = new Version(cliVersion).isAtLeast(MIN_CLI_VERSION_PASSWORD_STDIN) && envVars.get("JFROG_CLI_PASSWORD_STDIN_SUPPORT", "false").equals("true");

        // Create an ArgumentListBuilder
        ArgumentListBuilder builder = new ArgumentListBuilder();

        // Call the addCredentialsArguments method
        JfStep.addCredentialsArguments(builder, jfrogPlatformInstance, job, launcher, passwordStdinSupported);

        // Verify the arguments
        assertTrue(builder.toList().contains(expectedOutput));
    }

    static Stream<Arguments> provideTestArguments() {
        String passwordFlag = "--password=";
        String passwordStdinFlag = "--password-stdin";
        EnvVars envVarsTrue = mock(EnvVars.class);
        when(envVarsTrue.get("JFROG_CLI_PASSWORD_STDIN_SUPPORT", "false")).thenReturn("true");
        EnvVars envVarsFalse = mock(EnvVars.class);
        when(envVarsFalse.get("JFROG_CLI_PASSWORD_STDIN_SUPPORT", "false")).thenReturn("false");
        // Min version for password stdin is 2.31.3
        return Stream.of(
                // Supported CLI version but Plugin Launcher
                Arguments.of("2.57.0", envVarsTrue, passwordStdinFlag),
                // Unsupported CLI version and Plugin Launcher
                Arguments.of("2.31.0", envVarsTrue, passwordFlag),
                // Unsupported Version
                Arguments.of("2.31.0", envVarsFalse, passwordFlag),
                // Supported CLI version and local launcher
                Arguments.of("2.57.0", envVarsFalse, passwordFlag),
                // Minimum supported CLI version for password stdin
                Arguments.of("2.31.3", envVarsFalse, passwordFlag),
                // Minimum supported CLI version for password stdin
                Arguments.of("2.31.3", envVarsTrue, passwordStdinFlag)
        );
    }
}

