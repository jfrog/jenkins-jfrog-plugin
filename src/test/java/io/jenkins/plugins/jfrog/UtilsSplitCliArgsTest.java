package io.jenkins.plugins.jfrog;

import hudson.util.ArgumentListBuilder;
import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static io.jenkins.plugins.jfrog.Utils.splitCliArgs;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/**
 * Tests for {@link Utils#splitCliArgs(String)}.
 * <p>
 * Double quotes group values containing whitespace and are stripped; nothing else is interpreted.
 * The cases below pin down the two characters that a shell-style tokenizer would otherwise consume:
 * the backslash and the apostrophe. Both must stay literal, because the arguments are passed
 * straight to the process and are never re-parsed by a shell.
 */
public class UtilsSplitCliArgsTest {

    @ParameterizedTest
    @MethodSource("quotingProvider")
    void groupsQuotedValuesAndStripsGroupingQuotes(String command, String[] expected) {
        assertArrayEquals(expected, splitCliArgs(command));
    }

    private static Stream<Arguments> quotingProvider() {
        return Stream.of(
                // The reported bug: a quoted value containing a space is a single argument.
                Arguments.of("rt mvn -Dsonar.projectName=\"C7 CMS\"",
                        new String[]{"rt", "mvn", "-Dsonar.projectName=C7 CMS"}),
                Arguments.of("rt bp \"My Build\" 12",
                        new String[]{"rt", "bp", "My Build", "12"}),
                Arguments.of("rt upload a.jar repo/ --target-props=\"k1=v 1;k2=v 2\"",
                        new String[]{"rt", "upload", "a.jar", "repo/", "--target-props=k1=v 1;k2=v 2"}),
                // Quotes in the middle of an argument still group, and are removed.
                Arguments.of("-Dp=a\"b c\"d", new String[]{"-Dp=ab cd"}),
                // An explicitly empty quoted value is preserved as an argument.
                Arguments.of("--foo=\"\"", new String[]{"--foo="}),
                // Unquoted commands behave exactly as they did before.
                Arguments.of("rt ping", new String[]{"rt", "ping"}),
                Arguments.of("rt upload *.jar repo/", new String[]{"rt", "upload", "*.jar", "repo/"})
        );
    }

    @ParameterizedTest
    @MethodSource("literalCharactersProvider")
    void keepsBackslashesAndApostrophesLiteral(String command, String[] expected) {
        assertArrayEquals(expected, splitCliArgs(command));
    }

    private static Stream<Arguments> literalCharactersProvider() {
        return Stream.of(
                // Windows paths, including a trailing backslash, survive untouched.
                Arguments.of("-Dpath=C:\\Users\\foo", new String[]{"-Dpath=C:\\Users\\foo"}),
                Arguments.of("-Dpath=C:\\Users\\foo\\", new String[]{"-Dpath=C:\\Users\\foo\\"}),
                // Consecutive backslashes are not collapsed (compare JENKINS-2584).
                Arguments.of("-Dpath=C:\\\\Users\\\\foo", new String[]{"-Dpath=C:\\\\Users\\\\foo"}),
                Arguments.of("rt upload \\\\server\\share\\f.jar repo/",
                        new String[]{"rt", "upload", "\\\\server\\share\\f.jar", "repo/"}),
                // A quoted Windows path keeps both its space and its trailing backslash.
                Arguments.of("rt upload d.txt \"C:\\Users\\my dir\\\" --flat=true",
                        new String[]{"rt", "upload", "d.txt", "C:\\Users\\my dir\\", "--flat=true"}),
                // Backslashes used by --regexp and --exclusions patterns are not escapes.
                Arguments.of("rt upload (.*)\\.jar repo/{1} --regexp=true",
                        new String[]{"rt", "upload", "(.*)\\.jar", "repo/{1}", "--regexp=true"}),
                // Apostrophes are literal and must not swallow the following argument.
                Arguments.of("-Dmsg=don't --flat=true", new String[]{"-Dmsg=don't", "--flat=true"}),
                Arguments.of("rt upload it's.txt repo/", new String[]{"rt", "upload", "it's.txt", "repo/"}),
                Arguments.of("-Ddesc=Bob's build", new String[]{"-Ddesc=Bob's", "build"}),
                // Single quotes therefore do not group.
                Arguments.of("-Ddesc='C7 CMS'", new String[]{"-Ddesc='C7", "CMS'"}),
                // An apostrophe inside a double-quoted value is kept as-is.
                Arguments.of("-Ddesc=\"it's here\"", new String[]{"-Ddesc=it's here"})
        );
    }

    @ParameterizedTest
    @MethodSource("whitespaceProvider")
    void treatsAllWhitespaceAsASeparator(String command, String[] expected) {
        assertArrayEquals(expected, splitCliArgs(command));
    }

    private static Stream<Arguments> whitespaceProvider() {
        return Stream.of(
                Arguments.of("rt\tping\tfoo", new String[]{"rt", "ping", "foo"}),
                // Freestyle jobs use a textarea, so commands may span lines.
                Arguments.of("rt upload\ndummy.txt repo/", new String[]{"rt", "upload", "dummy.txt", "repo/"}),
                Arguments.of("rt    ping", new String[]{"rt", "ping"}),
                Arguments.of("   rt ping   ", new String[]{"rt", "ping"}),
                Arguments.of("", new String[]{}),
                Arguments.of("   ", new String[]{})
        );
    }

    /**
     * Commands that contain no double quote must be tokenized exactly as the previous
     * implementation (StringUtils.split) tokenized them. Without a double quote the parser never
     * enters its quoted state, so it only splits on whitespace and copies every other character
     * verbatim, which is what plain whitespace splitting did. This guarantees that adding quote
     * support cannot change the behaviour of any existing unquoted command, for any CLI subcommand.
     */
    @ParameterizedTest
    @MethodSource("unquotedCommandsProvider")
    void unquotedCommandsTokenizeExactlyAsPlainWhitespaceSplitting(String command) {
        assertArrayEquals(StringUtils.split(command), splitCliArgs(command),
                "tokenizing changed for an unquoted command: " + command);
    }

    private static Stream<String> unquotedCommandsProvider() {
        return Stream.of(
                // Generic and configuration commands
                "rt ping", "c show", "-v",
                "c add my-server --url=https://x.jfrog.io --interactive=false",
                // Artifactory file operations
                "rt upload dummy.txt repo/", "rt u target/*.jar libs-release-local/",
                "rt download repo/path/ out/", "rt copy repo-a/x.jar repo-b/",
                "rt move repo-a/x.jar repo-b/", "rt delete repo/old/ --quiet",
                "rt search repo/*.jar", "rt set-props repo/a.jar key=value",
                "rt upload (.*)\\.jar repo/{1} --regexp=true",
                "rt upload . repo/ --exclusions=*.txt;*.md",
                "rt upload a.jar repo/ --target-props=k1=v1;k2=v2",
                "rt upload --spec=s.json --spec-vars=key1=val1;key2=val2",
                "rt upload a.jar repo/ --build-name=my-build --build-number=42",
                "rt bp my-build 42", "rt bpr my-build 42 target-repo",
                // Package manager commands
                "mvn clean install -DskipTests",
                "mvn clean install -Dsonar.host.url=http://sonar:9000",
                "gradle clean artifactoryPublish", "npm install", "npm publish",
                "nuget restore sln.sln", "dotnet restore", "go build ./...",
                "docker push my-image:latest", "helm push chart.tgz repo",
                // Security commands
                "audit --licenses --format=json", "scan a.jar", "xr scan --spec=s.json",
                "ds rbc release-bundle 1.0.0 --spec=s.json",
                "rt curl -XGET /api/system/ping",
                // Windows paths without quotes, including a trailing and a doubled backslash
                "rt upload C:\\build\\out\\a.jar repo/", "rt upload C:\\build\\out\\ repo/",
                "rt upload \\\\server\\share\\a.jar repo/", "rt upload C:\\\\build\\\\a.jar repo/",
                // Apostrophes without quotes
                "rt upload it's.txt repo/", "rt bp bob's-build 42",
                "rt upload a.jar repo/ --target-props=desc=don't",
                // Unusual whitespace
                "rt\tping", "rt   ping", "  rt ping  "
        );
    }

    /**
     * A quoted value containing a space is re-quoted by {@link ArgumentListBuilder#toWindowsCommand()}
     * before it is handed to cmd.exe. This pins the resulting command line so that a change in the
     * re-quoting behaviour is noticed here rather than on a Windows agent.
     */
    @Test
    void quotedValueIsReQuotedForWindows() {
        ArgumentListBuilder builder = new ArgumentListBuilder();
        builder.add("jf.exe").add(splitCliArgs("rt mvn -Dsonar.projectName=\"C7 CMS\""));

        assertArrayEquals(
                new String[]{"cmd.exe", "/C", "\"jf.exe", "rt", "mvn", "\"-Dsonar.projectName=C7 CMS\"",
                        "&&", "exit", "%%ERRORLEVEL%%\""},
                builder.toWindowsCommand().toCommandArray());
    }
}
