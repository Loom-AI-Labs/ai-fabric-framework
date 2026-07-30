# Maven Central Release Guide

AI Fabric Framework publishes to **Maven Central** via the Sonatype Central Portal.

- Group: `io.github.loom-ai-labs`
- BOM artifact: `ai-fabric-bom`
- Release tag format: `ai-fabric-framework-v<version>`
- Current release: `0.5.0`

## Consume From Maven Central

No repository configuration and no credentials are needed to consume — Central is the default
Maven repository.

```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>io.github.loom-ai-labs</groupId>
      <artifactId>ai-fabric-bom</artifactId>
      <version>0.5.0</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>
```

## One-Time Setup (maintainer)

Publishing requires credentials that are **not** stored in this repository. Configure them once
as GitHub Actions repository secrets:

1. **Sonatype Central Portal account + namespace**
   - Sign in at https://central.sonatype.com and register the `io.github.loom-ai-labs`
     namespace (GitHub-based verification is automatic for an `io.github.<org>` namespace you own).
   - Generate a **user token** (Account → Generate User Token).
   - Add secrets: `CENTRAL_TOKEN_USERNAME`, `CENTRAL_TOKEN_PASSWORD`.

2. **GPG signing key** (Central requires every artifact to be signed)
   - Generate a key: `gpg --gen-key`
   - Publish the public key: `gpg --keyserver keyserver.ubuntu.com --send-keys <KEY_ID>`
   - Export the private key: `gpg --armor --export-secret-keys <KEY_ID>`
   - Add secrets: `MAVEN_GPG_PRIVATE_KEY` (the armored private key), `MAVEN_GPG_PASSPHRASE`.

## Verify Before Release

```bash
.github/scripts/validate-framework-release-guards.sh
mvn -B -V --no-transfer-progress -f ai-infrastructure-module/pom.xml \
  -Dai.vector-db.lucene.cleanup-on-close=true \
  -Prelease \
  -pl '!integration-Testing/testcontainers-support,!integration-Testing/integration-tests,!integration-Testing/relationship-query-integration-tests,!integration-Testing/chat-session-integration-tests,!integration-Testing/behavior-integration-tests' \
  install
mvn -B -V --no-transfer-progress -f examples/minimal-spring-boot/pom.xml compile
mvn -B -V --no-transfer-progress -f examples/real-apps/pom.xml install
```

## Release

The `central` Maven profile (in `ai-infrastructure-module/pom.xml`) GPG-signs all artifacts and
uploads them through the `central-publishing-maven-plugin`. The `release` profile attaches the
`-sources.jar` and `-javadoc.jar` Central requires.

Tag and create a GitHub Release; the release workflow publishes automatically:

```bash
git tag -a ai-fabric-framework-v0.5.0 -m "AI Fabric Framework 0.5.0"
git push origin ai-fabric-framework-v0.5.0
```

Then create a GitHub Release from the tag. The workflow runs:

```bash
mvn -B -V --no-transfer-progress -f ai-infrastructure-module/pom.xml \
  -Prelease,central \
  -pl '!integration-Testing/vector-contract-tests,!integration-Testing/testcontainers-support,!integration-Testing/integration-tests,!integration-Testing/relationship-query-integration-tests,!integration-Testing/chat-session-integration-tests,!integration-Testing/behavior-integration-tests' \
  deploy
```

With `autoPublish` enabled, the deployment is validated and released to Central without a manual
portal step.

## Patch Releases And Published Tags

Maven Central releases are immutable. If a release tag has already triggered publication or a
version is visible on Central, do not move or recreate that tag. Make the correction on the release
branch, bump Maven versions to the next patch version, and publish a new tag such as
`ai-fabric-framework-v0.5.1`.

Use `curl` before publishing to confirm whether a version already exists:

```bash
curl -s -o /dev/null -w '%{http_code}\n' \
  https://repo1.maven.org/maven2/io/github/loom-ai-labs/ai-fabric-bom/0.5.0/ai-fabric-bom-0.5.0.pom
```

## Boundary

This release is the framework only. It must not include private product code, deployment
credentials, customer configuration, or private operating context.
