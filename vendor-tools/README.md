# Vendoring `build-info-extractor-maven3`

`generate-resources` runs `vendor-build-info-extractor-maven3.sh`, which stages the extractor
jar in `maven-extractor-lib/`. `PlexusModuleContributor` injects it (plus Jackson/Commons from
the same directory) into the forked Maven JVM.

The extractor remains a compile dependency (transitives excluded) so Jenkins-side code can
reference `BuildInfoRecorder`. Maven 3.9.12+ needs extractor 2.43.6 or later
([build-info#841](https://github.com/jfrog/build-info/issues/841)).

```
vendor-tools/vendor-build-info-extractor-maven3.sh <version> [output-jar]
```
