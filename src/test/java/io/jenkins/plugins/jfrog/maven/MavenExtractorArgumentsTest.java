package io.jenkins.plugins.jfrog.maven;

import hudson.EnvVars;
import hudson.util.ArgumentListBuilder;
import org.jfrog.build.api.BuildInfoConfigProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.jfrog.build.api.BuildInfoConfigProperties.ENV_PROPERTIES_FILE_KEY;
import static org.jfrog.build.api.BuildInfoConfigProperties.ENV_PROPERTIES_FILE_KEY_IV;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MavenExtractorArgumentsTest {

    @Test
    void addsPropsFileAsSingleTokenAndOmitsEncryptionKeys() {
        EnvVars env = new EnvVars();
        String propsPath = "/tmp/j h-workspace/jfrog-buildinfo.properties";
        env.put(BuildInfoConfigProperties.ENV_BUILDINFO_PROPFILE, propsPath);
        env.put(ENV_PROPERTIES_FILE_KEY, "aes-key-must-not-appear");
        env.put(ENV_PROPERTIES_FILE_KEY_IV, "aes-iv-must-not-appear");

        ArgumentListBuilder args = new ArgumentListBuilder();
        MavenNativeExtractorListener.addExtractorLaunchArguments(args, env);
        List<String> tokens = args.toList();

        assertTrue(tokens.stream().anyMatch(token ->
                token.equals("-D" + BuildInfoConfigProperties.PROP_PROPS_FILE + "=" + propsPath)));
        assertTrue(tokens.stream().anyMatch(token ->
                token.equals("-D" + BuildInfoConfigProperties.ACTIVATE_RECORDER + "=true")));
        assertFalse(tokens.stream().anyMatch(token -> token.contains("aes-key-must-not-appear")));
        assertFalse(tokens.stream().anyMatch(token -> token.contains("aes-iv-must-not-appear")));
        assertFalse(tokens.stream().anyMatch(token -> token.contains(ENV_PROPERTIES_FILE_KEY)));
        assertFalse(tokens.stream().anyMatch(token -> token.contains(ENV_PROPERTIES_FILE_KEY_IV)));
    }

    @Test
    void skipsWhenPropertiesPathIsMissing() {
        ArgumentListBuilder args = new ArgumentListBuilder();
        args.add("existing");

        MavenNativeExtractorListener.addExtractorLaunchArguments(args, new EnvVars());

        assertTrue(args.toList().equals(List.of("existing")));
    }
}
