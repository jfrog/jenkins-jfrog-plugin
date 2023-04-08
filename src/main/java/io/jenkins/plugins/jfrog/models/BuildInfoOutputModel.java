package io.jenkins.plugins.jfrog.models;

import lombok.Getter;
import lombok.Setter;

/**
 * The output JSON model of the 'jf rt build-publish' command.
 *
 * @author yahavi
 **/
@Getter
@Setter
public class BuildInfoOutputModel {
    private String buildInfoUiUrl;
}
