package io.jenkins.plugins.jfrog;

import com.cloudbees.hudson.plugins.folder.Folder;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.CredentialsStore;
import com.cloudbees.plugins.credentials.domains.Domain;
import com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl;
import hudson.Launcher;
import hudson.model.FreeStyleProject;
import hudson.util.ArgumentListBuilder;
import hudson.util.Secret;
import io.jenkins.plugins.jfrog.configuration.CredentialsConfig;
import io.jenkins.plugins.jfrog.configuration.FolderServerCredentialsMapping;
import io.jenkins.plugins.jfrog.configuration.JFrogFolderProperty;
import io.jenkins.plugins.jfrog.configuration.JFrogPlatformInstance;
import io.jenkins.plugins.jfrog.jenkins.EnableJenkins;
import org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Verifies that folder-level credential overrides are applied when building jf config arguments.
 */
@EnableJenkins
class JfStepFolderCredentialsTest {

    @Test
    void addCredentialsArgumentsUsesFolderOverrideInsteadOfGlobal(JenkinsRule r) throws Exception {
        CredentialsStore rootStore = CredentialsProvider.lookupStores(r.jenkins).iterator().next();
        rootStore.addCredentials(Domain.global(), new UsernamePasswordCredentialsImpl(
                CredentialsScope.GLOBAL, "global-cred", null, "global-user", "global-pass"));

        Folder folder = r.jenkins.createProject(Folder.class, "team");
        CredentialsStore folderStore = CredentialsProvider.lookupStores(folder).iterator().next();
        folderStore.addCredentials(Domain.global(), new UsernamePasswordCredentialsImpl(
                CredentialsScope.GLOBAL, "folder-cred", null, "folder-user", "folder-pass"));
        folder.addProperty(new JFrogFolderProperty(Collections.singletonList(
                new FolderServerCredentialsMapping("acme", "folder-cred"))));

        FreeStyleProject job = folder.createProject(FreeStyleProject.class, "job");
        JFrogPlatformInstance instance = new JFrogPlatformInstance(
                "acme",
                "https://example.jfrog.io",
                new CredentialsConfig(Secret.fromString(""), Secret.fromString(""), Secret.fromString(""), "global-cred"),
                "",
                "",
                "");

        ArgumentListBuilder builder = new ArgumentListBuilder();
        JfStep.addCredentialsArguments(builder, instance, job, mock(Launcher.ProcStarter.class), false);

        assertTrue(
                builder.toList().contains("--user=folder-user"),
                "Expected folder override credentials, got: " + builder.toList());
        assertFalse(
                builder.toList().contains("--user=global-user"),
                "Global credentials must not be used when a folder override exists: " + builder.toList());
    }

    @Test
    void addCredentialsArgumentsFallsBackToGlobalWhenNoFolderOverride(JenkinsRule r) throws Exception {
        CredentialsStore rootStore = CredentialsProvider.lookupStores(r.jenkins).iterator().next();
        rootStore.addCredentials(Domain.global(), new UsernamePasswordCredentialsImpl(
                CredentialsScope.GLOBAL, "global-cred", null, "global-user", "global-pass"));

        Folder folder = r.jenkins.createProject(Folder.class, "team-no-override");
        FreeStyleProject job = folder.createProject(FreeStyleProject.class, "job");
        JFrogPlatformInstance instance = new JFrogPlatformInstance(
                "acme",
                "https://example.jfrog.io",
                new CredentialsConfig(Secret.fromString(""), Secret.fromString(""), Secret.fromString(""), "global-cred"),
                "",
                "",
                "");

        ArgumentListBuilder builder = new ArgumentListBuilder();
        JfStep.addCredentialsArguments(builder, instance, job, mock(Launcher.ProcStarter.class), false);

        assertTrue(
                builder.toList().contains("--user=global-user"),
                "Expected global credentials when no folder override exists, got: " + builder.toList());
    }

    @Test
    void addCredentialsArgumentsUsesFolderScopedAccessToken(JenkinsRule r) throws Exception {
        CredentialsStore rootStore = CredentialsProvider.lookupStores(r.jenkins).iterator().next();
        rootStore.addCredentials(Domain.global(), new UsernamePasswordCredentialsImpl(
                CredentialsScope.GLOBAL, "global-cred", null, "global-user", "global-pass"));

        Folder folder = r.jenkins.createProject(Folder.class, "team-token");
        CredentialsStore folderStore = CredentialsProvider.lookupStores(folder).iterator().next();
        folderStore.addCredentials(Domain.global(), new StringCredentialsImpl(
                CredentialsScope.GLOBAL, "folder-token", "folder access token", Secret.fromString("secret-token-value")));
        folder.addProperty(new JFrogFolderProperty(Collections.singletonList(
                new FolderServerCredentialsMapping("acme", "folder-token"))));

        FreeStyleProject job = folder.createProject(FreeStyleProject.class, "job");
        JFrogPlatformInstance instance = new JFrogPlatformInstance(
                "acme",
                "https://example.jfrog.io",
                new CredentialsConfig(Secret.fromString(""), Secret.fromString(""), Secret.fromString(""), "global-cred"),
                "",
                "",
                "");

        ArgumentListBuilder builder = new ArgumentListBuilder();
        JfStep.addCredentialsArguments(builder, instance, job, mock(Launcher.ProcStarter.class), false);

        assertTrue(
                builder.toList().contains("--access-token=secret-token-value"),
                "Expected folder-scoped access token to be used, got: " + builder.toList());
        assertFalse(
                builder.toList().contains("--user=global-user"),
                "Global credentials must not be used when a folder access-token override exists: " + builder.toList());
    }
}
