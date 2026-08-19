package io.jenkins.plugins.jfrog;

import hudson.EnvVars;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.Job;
import hudson.util.ArgumentListBuilder;
import io.jenkins.plugins.jfrog.configuration.CredentialsConfig;
import io.jenkins.plugins.jfrog.configuration.JFrogPlatformInstance;
import org.jfrog.build.client.Version;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.stream.Stream;

import static io.jenkins.plugins.jfrog.JfStep.Execution.decodeDPropertyValues;
import static io.jenkins.plugins.jfrog.JfStep.Execution.getJFrogCLIPath;
import static io.jenkins.plugins.jfrog.JfStep.Execution.shouldDecodeProps;
import static io.jenkins.plugins.jfrog.JfStep.MIN_CLI_VERSION_PASSWORD_STDIN;
import static io.jenkins.plugins.jfrog.JfrogInstallation.JFROG_BINARY_PATH;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.*;
/**
 * @author yahavi
 **/
public class JfStepTest {

    @ParameterizedTest
    @MethodSource("jfrogCLIPathProvider")
    void getJFrogCLIPathTest(EnvVars inputEnvVars, boolean isWindows, String expectedOutput) {
        Assertions.assertEquals(expectedOutput, getJFrogCLIPath(inputEnvVars, isWindows));
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
        when(procStarter.quiet(anyBoolean())).thenReturn(procStarter);
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
     * @param isPluginLauncher Whether the launcher is a plugin launcher
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

    private static Stream<Arguments> provideTestArguments() {
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

    @Test
    void testDecodeDPropertyValues() {
        // URL-encoded values decoded
        assertArrayEquals(
                new String[]{"-Ddeploy.scm.location=https://checkout@scm.example.com/scm/repo.git"},
                decodeDPropertyValues(new String[]{"-Ddeploy.scm.location=https%3A%2F%2Fcheckout%40scm.example.com%2Fscm%2Frepo.git"})
        );
        // URL-encoded branch decoded
        assertArrayEquals(
                new String[]{"-Ddeploy.scm.branch=feature/26.08"},
                decodeDPropertyValues(new String[]{"-Ddeploy.scm.branch=feature%2F26.08"})
        );
        // Decoded value passes through unchanged
        assertArrayEquals(
                new String[]{"-Ddeploy.scm.location=https://checkout@scm.example.com/scm/repo.git"},
                decodeDPropertyValues(new String[]{"-Ddeploy.scm.location=https://checkout@scm.example.com/scm/repo.git"})
        );
        // Non -D arg left unchanged
        assertArrayEquals(
                new String[]{"mvn", "deploy", "-DskipTests"},
                decodeDPropertyValues(new String[]{"mvn", "deploy", "-DskipTests"})
        );
        // Invalid percent sequence kept as-is
        assertArrayEquals(
                new String[]{"-Dkey=100%"},
                decodeDPropertyValues(new String[]{"-Dkey=100%"})
        );
        // + not decoded as space
        assertArrayEquals(
                new String[]{"-Ddeploy.committer=Sara++Ngo"},
                decodeDPropertyValues(new String[]{"-Ddeploy.committer=Sara++Ngo"})
        );
    }

    private static final String[] ENCODED_MVN_ARGS = new String[]{"mvn", "deploy", "-Ddeploy.scm.branch=feature%2F26.08"};

    @ParameterizedTest
    @MethodSource("decodePropsProvider")
    void shouldDecodePropsTest(String envValue, String[] args, boolean expected) {
        EnvVars env = new EnvVars();
        if (envValue != null) {
            env.put(JfStep.JFROG_CLI_DECODE_PROPS, envValue);
        }
        assertEquals(expected, shouldDecodeProps(env, args));
    }

    private static Stream<Arguments> decodePropsProvider() {
        return Stream.of(
                // Environment variable unset or disabled - arguments are passed to the CLI unchanged.
                Arguments.of(null, ENCODED_MVN_ARGS, false),
                Arguments.of("false", ENCODED_MVN_ARGS, false),
                Arguments.of("", ENCODED_MVN_ARGS, false),
                // Environment variable enabled - the check is case-insensitive.
                Arguments.of("true", ENCODED_MVN_ARGS, true),
                Arguments.of("TRUE", ENCODED_MVN_ARGS, true),
                Arguments.of("True", ENCODED_MVN_ARGS, true),
                // Enabled, but the command isn't 'jf mvn' - decoding does not apply.
                Arguments.of("true", new String[]{"rt", "upload", "-Dkey=a%2Fb"}, false),
                Arguments.of("true", new String[]{}, false)
        );
    }
}

