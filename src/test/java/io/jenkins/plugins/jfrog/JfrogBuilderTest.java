package io.jenkins.plugins.jfrog;

import hudson.EnvVars;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.BuildListener;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.PrintStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for JfrogBuilder
 * 
 * @author Jenkins JFrog Plugin Team
 */
public class JfrogBuilderTest {

    @Mock
    private AbstractBuild<?, ?> build;

    @Mock
    private Launcher launcher;

    @Mock
    private BuildListener listener;

    @Mock
    private FilePath workspace;

    @Mock
    private PrintStream logger;

    private JfrogBuilder builder;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        builder = new JfrogBuilder("jf rt ping");
    }

    @Test
    void testBuilderCreation() {
        assertNotNull(builder);
        assertEquals("jf rt ping", builder.getCommand());
    }

    @Test
    void testBuilderCreationWithNullCommand() {
        JfrogBuilder emptyBuilder = new JfrogBuilder(null);
        assertNotNull(emptyBuilder);
        assertNull(emptyBuilder.getCommand());
    }

    @Test
    void testSetCommand() {
        builder.setCommand("jf rt u target/ my-repo/");
        assertEquals("jf rt u target/ my-repo/", builder.getCommand());
    }

    @Test
    void testSetCommandWithJfrogPrefix() {
        builder.setCommand("jfrog rt ping");
        assertEquals("jfrog rt ping", builder.getCommand());
    }

    @Test
    void testDescriptorDisplayName() {
        JfrogBuilder.DescriptorImpl descriptor = new JfrogBuilder.DescriptorImpl();
        assertEquals("Run JFrog CLI", descriptor.getDisplayName());
    }

    @Test
    void testDescriptorIsApplicable() {
        JfrogBuilder.DescriptorImpl descriptor = new JfrogBuilder.DescriptorImpl();
        assertTrue(descriptor.isApplicable(AbstractProject.class));
    }

    @Test
    void testGetJFrogCLIPathWithEnvironmentVariable() {
        EnvVars env = new EnvVars();
        env.put("JFROG_BINARY_PATH", "/opt/jfrog");
        
        String pathUnix = JfrogBuilder.getJFrogCLIPath(env, false);
        assertEquals("/opt/jfrog/jf", pathUnix);
        
        String pathWindows = JfrogBuilder.getJFrogCLIPath(env, true);
        assertEquals("\\opt\\jfrog\\jf.exe", pathWindows);
    }

    @Test
    void testGetJFrogCLIPathWithoutEnvironmentVariable() {
        EnvVars env = new EnvVars();
        
        String pathUnix = JfrogBuilder.getJFrogCLIPath(env, false);
        assertEquals("jf", pathUnix);
        
        String pathWindows = JfrogBuilder.getJFrogCLIPath(env, true);
        assertEquals("jf.exe", pathWindows);
    }

    @Test
    void testPerformWithNullWorkspace() throws Exception {
        when(build.getWorkspace()).thenReturn(null);
        when(listener.error("Workspace is null")).thenReturn(null);
        
        builder.setCommand("rt ping");
        boolean result = builder.perform(build, launcher, listener);
        
        assertFalse(result);
        verify(listener).error("Workspace is null");
    }

    @Test
    void testPerformWithNoCommand() throws Exception {
        JfrogBuilder emptyBuilder = new JfrogBuilder(null);
        when(build.getWorkspace()).thenReturn(workspace);
        when(build.getEnvironment(listener)).thenReturn(new EnvVars());
        when(listener.error("No JFrog CLI command provided")).thenReturn(null);
        when(listener.getLogger()).thenReturn(logger);
        
        boolean result = emptyBuilder.perform(build, launcher, listener);
        
        assertFalse(result);
        verify(listener).error("No JFrog CLI command provided");
    }

    @Test
    void testPerformWithInvalidCommand() throws Exception {
        JfrogBuilder invalidBuilder = new JfrogBuilder("maven clean install");
        when(build.getWorkspace()).thenReturn(workspace);
        when(build.getEnvironment(listener)).thenReturn(new EnvVars());
        when(listener.error("JFrog CLI command must start with 'jf' or 'jfrog' followed by a subcommand (e.g., 'jf rt ping' or 'jfrog rt ping')")).thenReturn(null);
        when(listener.getLogger()).thenReturn(logger);
        
        boolean result = invalidBuilder.perform(build, launcher, listener);
        
        assertFalse(result);
        verify(listener).error("JFrog CLI command must start with 'jf' or 'jfrog' followed by a subcommand (e.g., 'jf rt ping' or 'jfrog rt ping')");
    }

    @Test
    void testPerformWithValidJfCommand() {
        JfrogBuilder validBuilder = new JfrogBuilder("jf rt ping");
        assertNotNull(validBuilder);
        assertEquals("jf rt ping", validBuilder.getCommand());
    }

    @Test
    void testPerformWithValidJfrogCommand() {
        JfrogBuilder validBuilder = new JfrogBuilder("jfrog rt ping");
        assertNotNull(validBuilder);
        assertEquals("jfrog rt ping", validBuilder.getCommand());
    }
}


