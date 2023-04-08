package io.jenkins.plugins.jfrog;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.model.TaskListener;
import hudson.remoting.RemoteOutputStream;
import org.apache.commons.io.output.TeeOutputStream;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/**
 * @author yahavi
 **/
public class JfTaskListener implements TaskListener {
    private final RemoteOutputStream output;
    private final TaskListener taskListener;

    public JfTaskListener(TaskListener taskListener, ByteArrayOutputStream outputStream) {
        this.output = new RemoteOutputStream(outputStream);
        this.taskListener = taskListener;
    }

    @NonNull
    @Override
    public PrintStream getLogger() {
        return new PrintStream(new TeeOutputStream(taskListener.getLogger(), output), true, StandardCharsets.UTF_8);
    }
}
