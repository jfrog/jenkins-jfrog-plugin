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
                new Object[]{",artifactory.jfrog.io,", "artifactory.jfrog.io"},

                // Jenkins wildcard patterns are normalized into the host suffixes expected by CLI tools
                new Object[]{"*.jfrog.io", ".jfrog.io"},
                new Object[]{"*jfrog.io", ".jfrog.io"},
                new Object[]{"artifactory*.jfrog.io", ".jfrog.io"},
                new Object[]{"*.*.jfrog.io", ".jfrog.io"},
                new Object[]{"*.jfrog.io\n*.acme.io", ".jfrog.io,.acme.io"},
                new Object[]{"*.jfrog.io;artifactory.acme.io", ".jfrog.io,artifactory.acme.io"},
                new Object[]{"*.jfrog.io | *.acme.io", ".jfrog.io,.acme.io"},
                // A lone '*' bypasses the proxy for all hosts and is understood as is
                new Object[]{"*", "*"},
                // Duplicates created by stripping the wildcards are removed
                new Object[]{"*.jfrog.io,.jfrog.io", ".jfrog.io"},
                new Object[]{"*.jfrog.io\nartifactory*.jfrog.io", ".jfrog.io"},
                // Patterns with nothing to match on after the wildcard fall back to their literal part
                new Object[]{"jfrog.*", "jfrog"},
                new Object[]{"10.0.*", "10.0"},
                new Object[]{"*.*", ""},
                // Entries that need no translation are left untouched
                new Object[]{".jfrog.io", ".jfrog.io"},
                new Object[]{"10.0.0.0/16", "10.0.0.0/16"},
                new Object[]{"artifactory.jfrog.io:8081", "artifactory.jfrog.io:8081"},
                new Object[]{"localhost", "localhost"}
        );
    }

    @Test
    public void createNoProxyValueTest() {
        assertEquals(expectedResult, createNoProxyValue(noProxy));
    }
}
