package io.jenkins.plugins.jfrog;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.Arrays;
import java.util.Collection;

import static io.jenkins.plugins.jfrog.ArtifactoryInstaller.*;
import static org.junit.Assert.assertEquals;

/**
 * @author yahavi
 **/
@RunWith(Parameterized.class)
public class ArtifactoryInstallerCheckCliVersionTest {
    private final String inputVersion;
    private final String expectedResult;

    public ArtifactoryInstallerCheckCliVersionTest(String inputVersion, String expectedResult) {
        this.inputVersion = inputVersion;
        this.expectedResult = expectedResult;
    }

    @Parameterized.Parameters
    public static Collection<Object[]> dataProvider() {
        return Arrays.asList(
                // Positive tests
                new Object[]{"", null},
                new Object[]{"2.6.1", null},
                new Object[]{"2.7.0", null},

                // Bad syntax
                new Object[]{"bad version", BAD_VERSION_PATTERN_ERROR},
                new Object[]{"1.2", BAD_VERSION_PATTERN_ERROR},
                new Object[]{"1.2.a", BAD_VERSION_PATTERN_ERROR},

                // Versions below minimum
                new Object[]{"2.5.9", LOW_VERSION_PATTERN_ERROR},
                new Object[]{"2.6.0", LOW_VERSION_PATTERN_ERROR}
        );
    }

    @Test
    public void testValidateCliVersion() {
        assertEquals(expectedResult, validateCliVersion(inputVersion).getMessage());
    }
}
