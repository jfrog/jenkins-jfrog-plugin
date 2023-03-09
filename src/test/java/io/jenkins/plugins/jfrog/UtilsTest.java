package io.jenkins.plugins.jfrog;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static io.jenkins.plugins.jfrog.Utils.splitByWhitespaces;

/**
 * @author yahavi
 **/
public class UtilsTest {

    @ParameterizedTest
    @MethodSource("splitByWhitespacesProvider")
    void splitByWhitespacesTest(String input, String[] expectedOutput) {
        Assertions.assertArrayEquals(expectedOutput, splitByWhitespaces(input));
    }

    private static Stream<Arguments> splitByWhitespacesProvider() {
        return Stream.of(
                Arguments.of("", new String[]{}),
                Arguments.of("   \t\n ", new String[]{}),
                Arguments.of("a", new String[]{"a"}),
                Arguments.of("a\"", new String[]{"a\""}),
                Arguments.of("a b", new String[]{"a", "b"}),
                Arguments.of("a \"b c\"", new String[]{"a", "\"b c\""}),
                Arguments.of("a & b", new String[]{"a", "&", "b"}),
                Arguments.of("a ^ b", new String[]{"a", "^", "b"}),
                Arguments.of("a$! #b", new String[]{"a$!", "#b"}),
                Arguments.of("a b c=\"\"", new String[]{"a", "b", "c=\"\""}),
                Arguments.of("a b c=\"abcd\"", new String[]{"a", "b", "c=\"abcd\""}),
                Arguments.of("a\n b c=\"ab bc\"", new String[]{"a", "b", "c=\"ab bc\""}),
                Arguments.of("a b c=\"ab bc\" ", new String[]{"a", "b", "c=\"ab bc\""}),
                Arguments.of("a b=\"asda \" c=\"ab\r\n bc\" ", new String[]{"a", "b=\"asda \"", "c=\"ab\r\n bc\""}),
                Arguments.of("\"a ab cde fgh\t \" b=\"asda \" c=\"ab bc\" ", new String[]{"\"a ab cde fgh\t \"", "b=\"asda \"", "c=\"ab bc\""})
        );
    }
}
