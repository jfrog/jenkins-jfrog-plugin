#!/usr/bin/env bash
# Snapshot logic for jenkins-jfrog-plugin, extracted from
# .github/workflows/snapshot.yml so it can be run both from the workflow
# and directly on a developer's machine.
#
# Expects the following environment variables to be set (the workflow sets
# these via the job-level `env:` block; to run locally, export the same
# variables and then invoke this script directly):
#   JFROG_CLI_BUILD_NUMBER  - build number to distribute (workflow sets this to github.run_number)
#   ARTIFACTORY_URL         - internal Artifactory URL used to resolve/deploy Maven artifacts
#   ARTIFACTORY_USER        - internal Artifactory user
#   ARTIFACTORY_APIKEY      - internal Artifactory API key
set -euo pipefail

# Configure JFrog CLI
jf c rm --quiet
jf c add internal --url=${ARTIFACTORY_URL} --access-token=${ARTIFACTORY_APIKEY}
jf mvnc --repo-resolve-releases ecosys-jenkins-repos --repo-resolve-snapshots ecosys-releases-snapshots --repo-deploy-snapshots ecosys-oss-snapshot-local --repo-deploy-releases ecosys-oss-release-local

# Audit
jf audit --fail=false

# Delete former snapshots
jf rt del "ecosys-oss-snapshot-local/org/jenkins-ci/plugins/jfrog/*" --quiet

# Build and publish
jf mvn clean install -U -B javadoc:jar source:jar -Denforcer.skip -DskipTests
jf rt bag && jf rt bce
jf rt bp

# Distribute release bundle
jf ds rbc ecosystem-jfrog-jenkins-plugin-snapshot ${JFROG_CLI_BUILD_NUMBER} --spec=./.jfrog-pipelines/specs/dev-rbc-filespec.json --sign
jf ds rbd ecosystem-jfrog-jenkins-plugin-snapshot ${JFROG_CLI_BUILD_NUMBER} --site="releases.jfrog.io" --sync
