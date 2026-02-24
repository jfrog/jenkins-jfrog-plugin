package io.jenkins.plugins.jfrog.integration;

import lombok.Getter;

@Getter
enum TestRepository {
    LOCAL_REPO("jenkins-jfrog-tests-local", RepoType.LOCAL),
    CLI_REMOTE_REPO("jenkins-jfrog-tests-cli-remote", RepoType.REMOTE);

    enum RepoType {
        LOCAL,
        REMOTE
    }

    private final String repoName;
    private final RepoType repoType;

    TestRepository(String repoName, RepoType repoType) {
        this.repoName = repoName;
        this.repoType = repoType;
    }

    @Override
    public String toString() {
        return getRepoName();
    }
}
