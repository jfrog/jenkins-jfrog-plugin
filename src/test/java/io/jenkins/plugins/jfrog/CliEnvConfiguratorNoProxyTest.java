package io.jenkins.plugins.jfrog;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.Arrays;
import java.util.Collection;

import static io.jenkins.plugins.jfrog.CliEnvConfigurator.noProxyExtractor;
import static org.junit.Assert.assertEquals;

/**
 * @author nathana
 **/
@RunWith(Parameterized.class)
public class CliEnvConfiguratorNoProxyTest {
    private final String noProxyList;
    private final String expectedResult;

    public CliEnvConfiguratorNoProxyTest(String noProxyList, String expectedResult) {
        this.noProxyList = noProxyList;
        this.expectedResult = expectedResult;
    }

    @Parameterized.Parameters
    public static Collection<Object[]> dataProvider() {
        return Arrays.asList(
                // Positive tests
                new Object[]{"artifactory.jfrog.io", "artifactory.jfrog.io"},
                new Object[]{"artifactory.jfrog.io    \n      artifactory1.jfrog.io          ", "artifactory.jfrog.io;artifactory1.jfrog.io"},
                new Object[]{"   artifactory.jfrog.io    \n  \r     artifactory1.jfrog.io;artifactory2.jfrog.io    \n      artifactory3.jfrog.io | artifactory4.jfrog.io    \n      artifactory5.jfrog.io ", "artifactory.jfrog.io;artifactory1.jfrog.io;artifactory2.jfrog.io;artifactory3.jfrog.io;artifactory4.jfrog.io;artifactory5.jfrog.io"},
                new Object[]{"\r\n", ""},
                new Object[]{";;;", ""},
                new Object[]{"artifactory.jfrog.io;", "artifactory.jfrog.io"},
                new Object[]{"artifactory.jfrog.io;artifactory1.jfrog.io", "artifactory.jfrog.io;artifactory1.jfrog.io"},
                new Object[]{"artifactory.jfrog.io;artifactory1.jfrog.io;artifactory2.jfrog.io;artifactory3.jfrog.io", "artifactory.jfrog.io;artifactory1.jfrog.io;artifactory2.jfrog.io;artifactory3.jfrog.io"},
                new Object[]{"artifactory.jfrog.io   \nartifactory1.jfrog.io", "artifactory.jfrog.io;artifactory1.jfrog.io"},
                new Object[]{"artifactory.jfrog.io \nartifactory1.jfrog.io\nartifactory2.jfrog.io  \n  artifactory3.jfrog.io", "artifactory.jfrog.io;artifactory1.jfrog.io;artifactory2.jfrog.io;artifactory3.jfrog.io"}
        );
    }

    @Test
    public void testValidateCliVersion() {
        assertEquals(expectedResult, noProxyExtractor(noProxyList));
    }
}
