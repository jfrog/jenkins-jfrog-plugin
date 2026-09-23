package io.jenkins.plugins.jfrog.maven;

import hudson.util.ArgumentListBuilder;
import org.jfrog.build.api.BuildInfoConfigProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MavenExtractorArgumentsTest {

    @Test
    void addsPropsFileAsSingleToken() {
        String propsPath = "/tmp/j h-workspace/jfrog-buildinfo.properties";

        ArgumentListBuilder args = new ArgumentListBuilder();
        MavenNativeExtractorListener.addExtractorLaunchArguments(args, propsPath);
        List<String> tokens = args.toList();

        // Encryption key/IV never pass through this method at all now - propsPath is the only
        // value it ever sees, so there's no env map for them to leak from.
        assertTrue(tokens.stream().anyMatch(token ->
                token.equals("-D" + BuildInfoConfigProperties.PROP_PROPS_FILE + "=" + propsPath)));
        assertTrue(tokens.stream().anyMatch(token ->
                token.equals("-D" + BuildInfoConfigProperties.ACTIVATE_RECORDER + "=true")));
    }

    @Test
    void skipsWhenPropertiesPathIsMissing() {
        ArgumentListBuilder args = new ArgumentListBuilder();
        args.add("existing");

        MavenNativeExtractorListener.addExtractorLaunchArguments(args, null);
        MavenNativeExtractorListener.addExtractorLaunchArguments(args, "");
        MavenNativeExtractorListener.addExtractorLaunchArguments(args, "   ");

        assertEquals(List.of("existing"), args.toList());
    }

    @Test
    void storesAndReturnsPropsPath() {
        MavenNativeExtractorListener.MavenExtractorArguments action =
                new MavenNativeExtractorListener.MavenExtractorArguments();
        String propsPath = "/tmp/workspace/jfrog-buildinfo.properties";

        action.setPropsPath(propsPath);
        ArgumentListBuilder args = action.intercept(new ArgumentListBuilder(), null);

        assertTrue(args.toList().stream().anyMatch(token ->
                token.equals("-D" + BuildInfoConfigProperties.PROP_PROPS_FILE + "=" + propsPath)));
    }
}
