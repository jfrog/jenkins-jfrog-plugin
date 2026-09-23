# Vendoring `build-info-extractor-maven3`

`org.jfrog.buildinfo:build-info-extractor-maven3` contains `BuildInfoRecorder` and the
Artifactory Eclipse resolver overrides this plugin contributes into Maven's own process for
Maven Project jobs (deploy/build-info capture **and** Resolve Repository).

During `generate-resources`, `vendor-build-info-extractor-maven3.sh` copies the **full**
upstream jar into `target/classes/maven-extractor-lib/`. That copy is what
`PlexusModuleContributor` injects into the forked Maven JVM.

The official extractor also remains a normal compile dependency so
`Which.jarFile(BuildInfoRecorder.class)` resolves on a clean machine. Jackson / Commons jars
for the forked Maven JVM are copied into the same `maven-extractor-lib/` directory and are
**not** added as Jenkins-side compile dependencies (Pipeline/Freestyle keep using
`jackson2-api` / `commons-lang3-api`).

## Maven 3.9.x note

Some Maven 3.9.12+ releases NPE when the extractor's `ArtifactoryEclipsePluginManager`
Plexus component is present (missing `prerequisitesCheckers` injection — see
[build-info#841](https://github.com/jfrog/build-info/issues/841)). When Resolve Repository
is configured, `MavenNativeExtractorEnvironment` fails the build with a clear error on
those versions instead of silently falling back to Central. Prefer Maven 3.8.x or
3.9.0–3.9.11 until an upstream extractor fix is available.

## Re-vendoring after a `buildinfo.version` bump

```
vendor-tools/vendor-build-info-extractor-maven3.sh <new-version>
```

That still installs a local artifact for manual use. The Maven build itself calls the same
script with an output path:

```
vendor-tools/vendor-build-info-extractor-maven3.sh <version> <output-jar>
```
