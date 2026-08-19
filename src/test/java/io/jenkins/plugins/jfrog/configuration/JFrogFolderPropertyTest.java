package io.jenkins.plugins.jfrog.configuration;

import com.cloudbees.hudson.plugins.folder.Folder;
import hudson.model.FreeStyleProject;
import io.jenkins.plugins.jfrog.jenkins.EnableJenkins;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Tests for folder-level JFrog credentials support.
 */
@EnableJenkins
class JFrogFolderPropertyTest {

    @Test
    void getCredentialsIdForServerReturnsConfiguredMapping() {
        JFrogFolderProperty property = new JFrogFolderProperty(Arrays.asList(
                new FolderServerCredentialsMapping("serverA", "cred-a"),
                // A mapping without a credentials id must be ignored.
                new FolderServerCredentialsMapping("serverB", "")));

        assertEquals("cred-a", property.getCredentialsIdForServer("serverA"));
        assertNull(property.getCredentialsIdForServer("serverB"));
        assertNull(property.getCredentialsIdForServer("unknown"));
        assertNull(property.getCredentialsIdForServer(null));
    }

    @Test
    void nullMappingsAreHandledGracefully() {
        JFrogFolderProperty property = new JFrogFolderProperty(null);
        assertNull(property.getCredentialsIdForServer("serverA"));
        assertEquals(Collections.emptyList(), property.getServerCredentialsMappings());
    }

    @Test
    void resolveReturnsNullWhenJobIsNotInAFolder(JenkinsRule r) throws Exception {
        FreeStyleProject job = r.createFreeStyleProject("top-level-job");
        assertNull(FolderCredentialsResolver.resolve(job, "serverA"));
    }

    @Test
    void resolveUsesNearestFolderOverride(JenkinsRule r) throws Exception {
        Folder parent = r.jenkins.createProject(Folder.class, "parent");
        parent.addProperty(new JFrogFolderProperty(Collections.singletonList(
                new FolderServerCredentialsMapping("serverA", "parent-cred"))));
        Folder child = parent.createProject(Folder.class, "child");
        child.addProperty(new JFrogFolderProperty(Collections.singletonList(
                new FolderServerCredentialsMapping("serverA", "child-cred"))));
        FreeStyleProject job = child.createProject(FreeStyleProject.class, "job");

        FolderCredentialsResolver.Resolution resolution = FolderCredentialsResolver.resolve(job, "serverA");

        assertNotNull(resolution);
        assertEquals("child-cred", resolution.getCredentialsId());
        assertSame(child, resolution.getContext());
    }

    @Test
    void resolveFallsBackToAncestorFolder(JenkinsRule r) throws Exception {
        Folder parent = r.jenkins.createProject(Folder.class, "parent-with-override");
        parent.addProperty(new JFrogFolderProperty(Collections.singletonList(
                new FolderServerCredentialsMapping("serverA", "parent-cred"))));
        // Child folder has no JFrog override.
        Folder child = parent.createProject(Folder.class, "child-without-override");
        FreeStyleProject job = child.createProject(FreeStyleProject.class, "job");

        FolderCredentialsResolver.Resolution resolution = FolderCredentialsResolver.resolve(job, "serverA");

        assertNotNull(resolution);
        assertEquals("parent-cred", resolution.getCredentialsId());
        assertSame(parent, resolution.getContext());
    }

    @Test
    void resolveFallsBackToGrandparentFolder(JenkinsRule r) throws Exception {
        Folder grandparent = r.jenkins.createProject(Folder.class, "grandparent-with-override");
        grandparent.addProperty(new JFrogFolderProperty(Collections.singletonList(
                new FolderServerCredentialsMapping("serverA", "grandparent-cred"))));
        // Neither the parent nor the child override serverA.
        Folder parent = grandparent.createProject(Folder.class, "parent-without-override");
        Folder child = parent.createProject(Folder.class, "child-without-override");
        FreeStyleProject job = child.createProject(FreeStyleProject.class, "job");

        FolderCredentialsResolver.Resolution resolution = FolderCredentialsResolver.resolve(job, "serverA");

        assertNotNull(resolution);
        assertEquals("grandparent-cred", resolution.getCredentialsId());
        assertSame(grandparent, resolution.getContext());
    }

    @Test
    void resolveReturnsNullForUnmappedServer(JenkinsRule r) throws Exception {
        Folder folder = r.jenkins.createProject(Folder.class, "folder-with-other-server");
        folder.addProperty(new JFrogFolderProperty(Collections.singletonList(
                new FolderServerCredentialsMapping("serverA", "cred-a"))));
        FreeStyleProject job = folder.createProject(FreeStyleProject.class, "job");

        assertNull(FolderCredentialsResolver.resolve(job, "serverB"));
    }
}
