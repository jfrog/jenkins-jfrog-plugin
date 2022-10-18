package io.jenkins.plugins.jfrog.plugins;


import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.common.*;
import com.cloudbees.plugins.credentials.domains.DomainRequirement;
import hudson.model.Item;
import hudson.security.ACL;
import hudson.util.ListBoxModel;
import hudson.util.Secret;
import io.jenkins.plugins.jfrog.configuration.Credentials;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.plaincredentials.StringCredentials;

import java.util.Collections;
import java.util.List;

import static com.cloudbees.plugins.credentials.CredentialsProvider.lookupCredentials;


public class PluginsUtils {
    public static StringCredentials accessTokenCredentialsLookup(String credentialsId) {
        return CredentialsMatchers.firstOrNull(
                lookupCredentials(StringCredentials.class, (Item) null),
                CredentialsMatchers.withId(credentialsId)
        );
    }

    /**
     * lookup for credentials configured using jenkins credentials plugin.
     *
     * @param credentialsId uniq id given to the configured credentials.
     * @param item          some item (job or folder).
     * @return credentials. an empty field can't be null, will be represented by empty string.
     */
    public static Credentials credentialsLookup(String credentialsId, Item item) {
        // Looking for accessToken
        StringCredentials accessCred = PluginsUtils.accessTokenCredentialsLookup(credentialsId);
        if (accessCred != null) {
            return new Credentials(Credentials.EMPTY_SECRET, Credentials.EMPTY_SECRET, accessCred.getSecret());
        } else {
            // Looking for username and password
            UsernamePasswordCredentials usernamePasswordCredentials = CredentialsMatchers.firstOrNull(
                    lookupCredentials(UsernamePasswordCredentials.class, item),
                    CredentialsMatchers.withId(credentialsId)
            );
            if (usernamePasswordCredentials != null) {
                return new Credentials(Secret.fromString(usernamePasswordCredentials.getUsername()),
                        usernamePasswordCredentials.getPassword(), Credentials.EMPTY_SECRET);
            }
            return Credentials.EMPTY_CREDENTIALS;
        }

    }

    /**
     * Populate credentials list from the Jenkins Credentials plugin. In use in UI jobs and in the Global configuration.
     *
     * @param project - Jenkins project
     * @return credentials list
     */
    public static ListBoxModel fillPluginCredentials(Item project) {
        List<DomainRequirement> domainRequirements = Collections.emptyList();
        return new StandardListBoxModel()
                .includeEmptyValue()
                // Add project scoped credentials:
                .includeMatchingAs(ACL.SYSTEM, project, StandardCredentials.class, domainRequirements,
                        CredentialsMatchers.anyOf(
                                CredentialsMatchers.instanceOf(StandardUsernamePasswordCredentials.class),
                                CredentialsMatchers.instanceOf(StringCredentials.class),
                                CredentialsMatchers.instanceOf(StandardCertificateCredentials.class)
                        ))
                // Add Jenkins system scoped credentials
                .includeMatchingAs(ACL.SYSTEM, Jenkins.get(), StandardCredentials.class, domainRequirements,
                        CredentialsMatchers.anyOf(
                                CredentialsMatchers.instanceOf(StandardUsernamePasswordCredentials.class),
                                CredentialsMatchers.instanceOf(StringCredentials.class),
                                CredentialsMatchers.instanceOf(StandardCertificateCredentials.class)
                        )
                );
    }
}
