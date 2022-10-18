package io.jenkins.plugins.jfrog;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.TaskListener;
import org.jenkinsci.plugins.workflow.flow.FlowExecution;
import org.jenkinsci.plugins.workflow.flow.FlowExecutionListener;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;

import java.io.IOException;

import static io.jenkins.plugins.jfrog.Utils.getWorkspace;

/**
 * This class implements a declarative pipelines jobs listener.
 *
 * @author gail
 */
@SuppressWarnings("unused")
@Extension
public class WorkflowListener extends FlowExecutionListener {
    /**
     * After the build is complete, clean up the temporary directories.
     *
     * @param execution The {@link FlowExecution} that has completed.
     */
    @Override
    public void onCompleted(@NonNull FlowExecution execution) {
        try {
            WorkflowRun build = getWorkflowRun(execution);
            Utils.deleteBuildJfrogHomeDir(getWorkspace(build.getParent()), String.valueOf(build.getNumber()), getTaskListener(execution));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private TaskListener getTaskListener(FlowExecution execution) throws IOException {
        return execution.getOwner().getListener();
    }

    private WorkflowRun getWorkflowRun(FlowExecution execution) throws IOException {
        return (WorkflowRun) execution.getOwner().getExecutable();
    }
}
