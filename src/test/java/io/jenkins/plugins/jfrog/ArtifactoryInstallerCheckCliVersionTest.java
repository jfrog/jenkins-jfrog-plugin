package io.jenkins.plugins.jfrog;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static io.jenkins.plugins.jfrog.ArtifactoryInstaller.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * @author yahavi
 **/

class ArtifactoryInstallerCheckCliVersionTest {

    static Stream<Arguments> dataProvider() {
        return Stream.of(
                // Positive tests
                Arguments.of("", null),
                Arguments.of("2.6.1", null),
                Arguments.of("2.7.0", null),

                // Bad syntax
                Arguments.of("bad version", BAD_VERSION_PATTERN_ERROR),
                Arguments.of("1.2", BAD_VERSION_PATTERN_ERROR),
                Arguments.of("1.2.a", BAD_VERSION_PATTERN_ERROR),

                // Versions below minimum
                Arguments.of("2.5.9", LOW_VERSION_PATTERN_ERROR),
                Arguments.of("2.6.0", LOW_VERSION_PATTERN_ERROR)
        );
    }

    @ParameterizedTest
    @MethodSource("dataProvider")
    void testValidateCliVersion(String inputVersion, String expectedResult) {
        assertEquals(expectedResult, validateCliVersion(inputVersion).getMessage());
    }
}
