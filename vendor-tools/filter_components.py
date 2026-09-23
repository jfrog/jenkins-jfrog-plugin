import xml.etree.ElementTree as ET
import sys

REMOVE_ROLES_IMPLS = {
    "org.jfrog.build.extractor.maven.resolver.ArtifactoryEclipseResolversHelper",
    "org.jfrog.build.extractor.maven.resolver.ArtifactoryEclipsePluginManager",
    "org.jfrog.build.extractor.maven.resolver.ArtifactoryEclipseMetadataResolver",
    "org.jfrog.build.extractor.maven.resolver.ArtifactoryEclipseArtifactResolver",
    "org.jfrog.build.extractor.maven.resolver.ArtifactoryEclipseRepositoryListener",
    "org.jfrog.build.extractor.maven.ArtifactoryProjectBuilder",
}

in_path, out_path = sys.argv[1], sys.argv[2]
tree = ET.parse(in_path)
root = tree.getroot()
components_el = root.find("components")

kept = []
removed = []
for comp in list(components_el):
    impl_el = comp.find("implementation")
    impl = impl_el.text.strip() if impl_el is not None and impl_el.text else ""
    if impl in REMOVE_ROLES_IMPLS:
        removed.append(impl)
        components_el.remove(comp)
    else:
        kept.append(impl)

print("Removed:", len(removed))
for r in removed:
    print("  -", r)
print("Kept:", len(kept))
for k in kept:
    print("  +", k)

tree.write(out_path, xml_declaration=False, encoding="unicode")
