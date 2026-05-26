package io.jenkins.plugins.jfrog;

import hudson.model.Action;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jfrog.build.api.util.NullLog;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

/**
 * @author yahavi
 **/
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AddBuildInfoActionTest {

    private static final String RT_BP_OUTPUT = ("13:14:38 [\uD83D\uDD35Info] Deploying build info...\n" +
            "13:14:40 [\uD83D\uDD35Info] Build info successfully deployed.\n" +
            "{\n" +
            "  \"buildInfoUiUrl\": \"http://127.0.0.1:8081/ui/builds/test/1/1682417678409/published?buildRepo=artifactory-build-info\"\n" +
            "}");
    private static final String EXPECTED_BUILD_INFO_URL = "http://127.0.0.1:8081/ui/builds/test/1/1682417678409/published?buildRepo=artifactory-build-info";

    @Captor
    private ArgumentCaptor<Action> valueCapture;

    @Mock
    private WorkflowRun run;

    static Stream<Arguments> positiveDataProvider() {
        return Stream.of(
                Arguments.of("rt bp", RT_BP_OUTPUT),
                Arguments.of("rt build-publish", RT_BP_OUTPUT)
        );
    }

    @ParameterizedTest
    @MethodSource("positiveDataProvider")
    void addBuildInfoActionPositiveTest(String command, String output) throws IOException {
        doNothing().when(run).addAction(valueCapture.capture());
        runCliCommand(command, output);

        Mockito.verify(run, times(1)).addAction(isA(Action.class));
        assertEquals(1, valueCapture.getAllValues().size());
        assertEquals(EXPECTED_BUILD_INFO_URL, valueCapture.getValue().getUrlName());
    }

    static Stream<Arguments> negativeDataProvider() {
        return Stream.of(
                Arguments.of("rt u a b", RT_BP_OUTPUT),
                Arguments.of("rt bp", "{ \"a\": \"b\" }"),
                Arguments.of("rt bp", "Illegal output"),
                Arguments.of("rt bp", "{ Illegal JSON }")
        );
    }

    @ParameterizedTest
    @MethodSource("negativeDataProvider")
    void addBuildInfoActionNegativeTest(String command, String output) throws IOException {
        runCliCommand(command, output);
        Mockito.verify(run, never()).addAction(isA(Action.class));
    }

    private void runCliCommand(String command, String output) throws IOException {
        try (ByteArrayOutputStream taskOutputStream = new ByteArrayOutputStream()) {
            taskOutputStream.writeBytes(output.getBytes(StandardCharsets.UTF_8));
            JfStep jfStep = new JfStep(command);
            JfStep.addBuildInfoActionIfNeeded(jfStep.getArgs(), new NullLog(), run, taskOutputStream);
        }
    }
}
