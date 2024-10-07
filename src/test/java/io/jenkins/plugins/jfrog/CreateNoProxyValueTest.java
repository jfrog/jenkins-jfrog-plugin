package io.jenkins.plugins.jfrog;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.Arrays;
import java.util.Collection;

import static io.jenkins.plugins.jfrog.CliEnvConfigurator.createNoProxyValue;
import static org.junit.Assert.assertEquals;

/**
 * @author nathana
 **/
@RunWith(Parameterized.class)
public class CreateNoProxyValueTest {
    private final String noProxy;
    private final String expectedResult;

    public CreateNoProxyValueTest(String noProxy, String expectedResult) {
        this.noProxy = noProxy;
        this.expectedResult = expectedResult;
    }

    @Parameterized.Parameters
    public static Collection<Object[]> dataProvider() {
        return Arrays.asList(
                new Object[]{"artifactory.jfrog.io", "artifactory.jfrog.io"},
                new Object[]{"artifactory.jfrog.io    \n      artifactory1.jfrog.io          ", "artifactory.jfrog.io,artifactory1.jfrog.io"},
                new Object[]{"   artifactory.jfrog.io    \n  \r     artifactory1.jfrog.io;artifactory2.jfrog.io    \n      artifactory3.jfrog.io | artifactory4.jfrog.io    \n      artifactory5.jfrog.io ", "artifactory.jfrog.io,artifactory1.jfrog.io,artifactory2.jfrog.io,artifactory3.jfrog.io,artifactory4.jfrog.io,artifactory5.jfrog.io"},
                new Object[]{"\r\n", ""},
                new Object[]{";;;", ""},
                new Object[]{",,,", ""},
                new Object[]{"artifactory.jfrog.io;", "artifactory.jfrog.io"},
                new Object[]{"artifactory.jfrog.io,artifactory1.jfrog.io", "artifactory.jfrog.io,artifactory1.jfrog.io"},
                new Object[]{"artifactory.jfrog.io;artifactory1.jfrog.io;artifactory2.jfrog.io;artifactory3.jfrog.io", "artifactory.jfrog.io,artifactory1.jfrog.io,artifactory2.jfrog.io,artifactory3.jfrog.io"},
                new Object[]{"artifactory.jfrog.io|artifactory1.jfrog.io|artifactory2.jfrog.io|artifactory3.jfrog.io", "artifactory.jfrog.io,artifactory1.jfrog.io,artifactory2.jfrog.io,artifactory3.jfrog.io"},
                new Object[]{"artifactory.jfrog.io\nartifactory1.jfrog.io", "artifactory.jfrog.io,artifactory1.jfrog.io"},
                new Object[]{"artifactory.jfrog.io \nartifactory1.jfrog.io\nartifactory2.jfrog.io  \n  artifactory3.jfrog.io", "artifactory.jfrog.io,artifactory1.jfrog.io,artifactory2.jfrog.io,artifactory3.jfrog.io"},
                new Object[]{";artifactory.jfrog.io;", "artifactory.jfrog.io"},
                new Object[]{",artifactory.jfrog.io,", "artifactory.jfrog.io"}
        );
    }

    @Test
    public void createNoProxyValueTest() {
        assertEquals(expectedResult, createNoProxyValue(noProxy));
    }
}
