package io.jenkins.plugins.jfrog;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static io.jenkins.plugins.jfrog.CliEnvConfigurator.createNoProxyValue;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * @author nathana
 **/
class CreateNoProxyValueTest {

    static Stream<Arguments> dataProvider() {
        return Stream.of(
                Arguments.of("artifactory.jfrog.io", "artifactory.jfrog.io"),
                Arguments.of("artifactory.jfrog.io    \n      artifactory1.jfrog.io          ", "artifactory.jfrog.io,artifactory1.jfrog.io"),
                Arguments.of("   artifactory.jfrog.io    \n  \r     artifactory1.jfrog.io;artifactory2.jfrog.io    \n      artifactory3.jfrog.io | artifactory4.jfrog.io    \n      artifactory5.jfrog.io ", "artifactory.jfrog.io,artifactory1.jfrog.io,artifactory2.jfrog.io,artifactory3.jfrog.io,artifactory4.jfrog.io,artifactory5.jfrog.io"),
                Arguments.of("\r\n", ""),
                Arguments.of(";;;", ""),
                Arguments.of(",,,", ""),
                Arguments.of("artifactory.jfrog.io;", "artifactory.jfrog.io"),
                Arguments.of("artifactory.jfrog.io,artifactory1.jfrog.io", "artifactory.jfrog.io,artifactory1.jfrog.io"),
                Arguments.of("artifactory.jfrog.io;artifactory1.jfrog.io;artifactory2.jfrog.io;artifactory3.jfrog.io", "artifactory.jfrog.io,artifactory1.jfrog.io,artifactory2.jfrog.io,artifactory3.jfrog.io"),
                Arguments.of("artifactory.jfrog.io|artifactory1.jfrog.io|artifactory2.jfrog.io|artifactory3.jfrog.io", "artifactory.jfrog.io,artifactory1.jfrog.io,artifactory2.jfrog.io,artifactory3.jfrog.io"),
                Arguments.of("artifactory.jfrog.io\nartifactory1.jfrog.io", "artifactory.jfrog.io,artifactory1.jfrog.io"),
                Arguments.of("artifactory.jfrog.io \nartifactory1.jfrog.io\nartifactory2.jfrog.io  \n  artifactory3.jfrog.io", "artifactory.jfrog.io,artifactory1.jfrog.io,artifactory2.jfrog.io,artifactory3.jfrog.io"),
                Arguments.of(";artifactory.jfrog.io;", "artifactory.jfrog.io"),
                Arguments.of(",artifactory.jfrog.io,", "artifactory.jfrog.io")
        );
    }

    @ParameterizedTest
    @MethodSource("dataProvider")
    void createNoProxyValueTest(String noProxy, String expectedResult) {
        assertEquals(expectedResult, createNoProxyValue(noProxy));
    }
}
