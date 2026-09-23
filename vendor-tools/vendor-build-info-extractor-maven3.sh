#!/usr/bin/env bash
# Stages TWO variants of org.jfrog.buildinfo:build-info-extractor-maven3 into
# maven-extractor-lib/ for the forked Maven JVM:
#
#   build-info-extractor-maven3-<version>.jar             (full: BuildInfoRecorder + resolver)
#   build-info-extractor-maven3-<version>-no-resolver.jar (BuildInfoRecorder only)
#
# The resolver / ArtifactoryProjectBuilder classes in the full jar override core Maven Plexus
# components (DefaultProjectBuilder, PluginManager). Some Maven 3.9.x releases NPE when those
# overrides are present - jfrog/build-info#841 - and critically, that NPE happens as soon as the
# jar is on the classpath at all, independent of whether Resolve Repository is even configured.
# ArtifactoryPlexusContributor picks which variant to inject per build: the full one only when
# Resolve Repository is set AND the detected Maven version is compatible; the no-resolver one
# otherwise, so plain deploy/build-info capture is never at risk from this bug regardless of
# Maven version.
#
# See pom.xml (search for "build-info-extractor-maven3") and README.md in this directory.
#
# Usage:
#   vendor-tools/vendor-build-info-extractor-maven3.sh <buildinfo.version>
#   vendor-tools/vendor-build-info-extractor-maven3.sh <buildinfo.version> <output-jar-path>
#
# The optional output path writes the full jar there (and the no-resolver variant alongside it)
# and skips install-file. The Maven generate-resources step uses that form so CI does not need a
# pre-installed artifact.
set -euo pipefail

VERSION="${1:?Usage: $0 <buildinfo.version> [output-jar-path]}"
OUTPUT_JAR="${2:-}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WORK_DIR="$(mktemp -d)"
trap 'rm -rf "$WORK_DIR"' EXIT

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

echo "Building the no-resolver variant ..." >&2
EXTRACT_DIR="$WORK_DIR/extracted"
mkdir -p "$EXTRACT_DIR"
(cd "$EXTRACT_DIR" && jar xf "$ORIGINAL_JAR")
rm -fv "$EXTRACT_DIR/org/jfrog/build/extractor/maven/resolver/ArtifactoryEclipseResolversHelper.class" \
       "$EXTRACT_DIR/org/jfrog/build/extractor/maven/resolver/ArtifactoryEclipsePluginManager.class" \
       "$EXTRACT_DIR/org/jfrog/build/extractor/maven/resolver/ArtifactoryEclipseMetadataResolver.class" \
       "$EXTRACT_DIR/org/jfrog/build/extractor/maven/resolver/ArtifactoryEclipseArtifactResolver.class" \
       "$EXTRACT_DIR/org/jfrog/build/extractor/maven/resolver/ArtifactoryEclipseRepositoryListener.class" \
       "$EXTRACT_DIR/org/jfrog/build/extractor/maven/ArtifactoryProjectBuilder.class"
python3 "$SCRIPT_DIR/filter_components.py" \
    "$EXTRACT_DIR/META-INF/plexus/components.xml" \
    "$EXTRACT_DIR/META-INF/plexus/components.xml.filtered"
mv "$EXTRACT_DIR/META-INF/plexus/components.xml.filtered" "$EXTRACT_DIR/META-INF/plexus/components.xml"
NO_RESOLVER_JAR="$WORK_DIR/build-info-extractor-maven3-${VERSION}-no-resolver.jar"
(cd "$EXTRACT_DIR" && jar cf "$NO_RESOLVER_JAR" .)

if [ -n "${OUTPUT_JAR}" ]; then
    OUTPUT_DIR="$(dirname "${OUTPUT_JAR}")"
    mkdir -p "$OUTPUT_DIR"
    cp "${ORIGINAL_JAR}" "${OUTPUT_JAR}"
    cp "${NO_RESOLVER_JAR}" "${OUTPUT_DIR}/build-info-extractor-maven3-${VERSION}-no-resolver.jar"
    printf 'Wrote full extractor jar to %s\n' "${OUTPUT_JAR}" >&2
    printf 'Wrote no-resolver extractor jar to %s\n' "${OUTPUT_DIR}/build-info-extractor-maven3-${VERSION}-no-resolver.jar" >&2
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
