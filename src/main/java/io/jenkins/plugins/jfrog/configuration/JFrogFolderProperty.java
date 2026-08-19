package io.jenkins.plugins.jfrog.configuration;

import com.cloudbees.hudson.plugins.folder.AbstractFolder;
import com.cloudbees.hudson.plugins.folder.AbstractFolderProperty;
import com.cloudbees.hudson.plugins.folder.AbstractFolderPropertyDescriptor;
import hudson.Extension;
import org.apache.commons.lang3.StringUtils;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A {@link com.cloudbees.hudson.plugins.folder.Folder Folder}-level property that lets users
 * override the credentials used for a globally configured JFrog server ID with credentials
 * scoped to that folder.
 * <p>
 * Previously, the only way to change the credentials of a JFrog server ID for a specific set of
 * jobs was to reconfigure the server manually through the CLI
 * ({@code jf config edit <server-id> --user <user> --password <password>}). With this property,
 * a folder can declare, per JFrog server ID, which folder-scoped credential to use. Jobs inside
 * the folder (or nested sub-folders) then pick up those credentials automatically when the plugin
 * runs {@code jf c add <server-id>}.
 */
public class JFrogFolderProperty extends AbstractFolderProperty<AbstractFolder<?>> {

    private final List<FolderServerCredentialsMapping> serverCredentialsMappings;

    @DataBoundConstructor
    public JFrogFolderProperty(List<FolderServerCredentialsMapping> serverCredentialsMappings) {
        this.serverCredentialsMappings = serverCredentialsMappings == null
                ? new ArrayList<>()
                : new ArrayList<>(serverCredentialsMappings);
    }

    public List<FolderServerCredentialsMapping> getServerCredentialsMappings() {
        return serverCredentialsMappings == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(serverCredentialsMappings);
    }

    /**
     * Return the folder-scoped credentials id configured for the given JFrog server ID, or
     * {@code null} if this folder does not override credentials for that server.
     *
     * @param serverId the JFrog server ID to look up
     * @return the overriding credentials id, or {@code null} if none is configured
     */
    public String getCredentialsIdForServer(String serverId) {
        if (StringUtils.isBlank(serverId)) {
            return null;
        }
        for (FolderServerCredentialsMapping mapping : getServerCredentialsMappings()) {
            if (mapping != null
                    && serverId.equals(mapping.getServerId())
                    && StringUtils.isNotBlank(mapping.getCredentialsId())) {
                return mapping.getCredentialsId();
            }
        }
        return null;
    }

    @Extension(optional = true)
    @Symbol("jfrogFolderCredentials")
    public static class DescriptorImpl extends AbstractFolderPropertyDescriptor {
        @Nonnull
        @Override
        public String getDisplayName() {
            return "JFrog folder-level credentials";
        }
    }
}
