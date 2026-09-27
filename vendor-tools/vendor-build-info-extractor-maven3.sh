#!/usr/bin/env bash
# Stages org.jfrog.buildinfo:build-info-extractor-maven3 for the forked Maven JVM.
# The native reporter always resolves through Artifactory, so only the full extractor is needed.
#
# See pom.xml (search for "build-info-extractor-maven3") and README.md in this directory.
#
# Usage:
#   vendor-tools/vendor-build-info-extractor-maven3.sh <buildinfo.version>
#   vendor-tools/vendor-build-info-extractor-maven3.sh <buildinfo.version> <output-jar-path>
#
# The optional output path copies the jar there and skips install-file.
set -euo pipefail

VERSION="${1:?Usage: $0 <buildinfo.version> [output-jar-path]}"
OUTPUT_JAR="${2:-}"
GROUP_PATH="org/jfrog/buildinfo/build-info-extractor-maven3"
ORIGINAL_JAR="$HOME/.m2/repository/${GROUP_PATH}/${VERSION}/build-info-extractor-maven3-${VERSION}.jar"

if [ ! -f "$ORIGINAL_JAR" ]; then
    echo "Resolving org.jfrog.buildinfo:build-info-extractor-maven3:${VERSION} via Maven..." >&2
    mvn -q dependency:get -Dartifact="org.jfrog.buildinfo:build-info-extractor-maven3:${VERSION}"
fi

if [ ! -f "$ORIGINAL_JAR" ]; then
    echo "ERROR: could not find or resolve $ORIGINAL_JAR" >&2
    exit 1
fi

# Sanity: the full jar must contain resolver classes or Resolve Repository is a lie.
# Use `jar` (bundled with the JDK), not `unzip`/`zip` - those are separate OS packages that are
# not reliably present, especially on Windows Git Bash runners. `jar` is guaranteed to be on
# PATH here: this script only runs as part of a Maven build, which already requires a JDK.
if ! jar tf "$ORIGINAL_JAR" | grep -q 'org/jfrog/build/extractor/maven/resolver/ArtifactoryEclipseArtifactResolver'; then
    echo "ERROR: $ORIGINAL_JAR is missing ArtifactoryEclipseArtifactResolver" >&2
    exit 1
fi

if [ -n "${OUTPUT_JAR}" ]; then
    OUTPUT_DIR="$(dirname "${OUTPUT_JAR}")"
    mkdir -p "$OUTPUT_DIR"
    cp "${ORIGINAL_JAR}" "${OUTPUT_JAR}"
    printf 'Wrote extractor jar to %s\n' "${OUTPUT_JAR}" >&2
else
    echo "Installing io.jenkins.plugins.jfrog.vendored:build-info-extractor-maven3-filtered:${VERSION} ..." >&2
    mvn install:install-file \
        -Dfile="$ORIGINAL_JAR" \
        -DgroupId=io.jenkins.plugins.jfrog.vendored \
        -DartifactId=build-info-extractor-maven3-filtered \
        -Dversion="$VERSION" \
        -Dpackaging=jar
    echo "Done. Set <buildinfo.version>${VERSION}</buildinfo.version> in pom.xml and rebuild." >&2
fi
