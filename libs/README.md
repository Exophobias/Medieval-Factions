

## Optional Welcome 2.1.0 compile contract

`welcome-api/` is a repository-local Maven repository for
`com.github.exophobias:patriamwelcome:2.1.0`. It contains **only ten public API classes**
from five maintained Welcome API sources (including the five nested records).
It has no runtime classes, plugin descriptor, resources, embedded POM, credentials or stub types.
The adjacent POM is parentless and has no dependencies. The owner keeps Welcome optional and
never bundles this provided/compileOnly contract into its plugin.

Prepared from the root-approved Java25 `javac -g` API compiler output in the existing task build.
The three prior document API classes match the earlier canonical bytecode exactly.
The five API sources are still the intended Welcome2.1.0 source change; final signed source and
full canonical artifact identity verification remain release-owner gates. This is compile-contract
provenance, not a claim of a completed full build, publication or gameplay test.

The sorted ZIP entries use timestamp2026-01-01, DEFLATE level9 and mode0644. JAR SHA256:
`2a1bd17ec7a0a017fa0821dc583fc98173faaed88f97f6b49547673cd31a34d0` (13105bytes); standalone POM SHA256: `72901157214f655e2a7b1dcc8d3a7d58a786e7b3aab54ee7f43f7a68cc1c98bc`.
`CHECKSUMS` records these exact supplied files and the Maven SHA1 sidecars. Integrity checks
alone cannot establish freshness against future API changes.

Gradle consumers resolve this one module exclusively from the local repository with transitives
disabled. Maven consumers use provided scope with all transitive dependencies excluded. Before
local Maven builds, verify and install the exact thin contract (CI does this after restoring caches):

```sh
cd libs
sha256sum -c CHECKSUMS
mvn -B --no-transfer-progress install:install-file \
  -Dfile=welcome-api/com/github/exophobias/patriamwelcome/2.1.0/patriamwelcome-2.1.0.jar \
  -DpomFile=welcome-api/com/github/exophobias/patriamwelcome/2.1.0/patriamwelcome-2.1.0.pom
```

Update all ten class files, the standalone descriptor, checksums and dependency pin together after
an approved source build. Never replace this with the full Welcome plugin or add runtime dependencies.
