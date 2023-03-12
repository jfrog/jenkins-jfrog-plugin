package io.jenkins.plugins.jfrog;

import hudson.EnvVars;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static io.jenkins.plugins.jfrog.JfStep.getJFrogCLIPath;
import static io.jenkins.plugins.jfrog.JfrogInstallation.JFROG_BINARY_PATH;

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
}
