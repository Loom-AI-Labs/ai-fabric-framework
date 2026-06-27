# Maven Central Release Guide

AI Fabric Framework publishes to **Maven Central** via the Sonatype Central Portal.

- Group: `io.github.loom-ai-labs`
- BOM artifact: `ai-fabric-bom`
- Release tag format: `ai-fabric-framework-v<version>`
- Current release: `0.3.0`

## Consume From Maven Central

No repository configuration and no credentials are needed to consume — Central is the default
Maven repository.

```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>io.github.loom-ai-labs</groupId>
      <artifactId>ai-fabric-bom</artifactId>
      <version>0.3.0</version>
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
mvn -B -V --no-transfer-progress -f ai-infrastructure-module/pom.xml clean install
mvn -B -V --no-transfer-progress -f examples/real-apps/pom.xml install
```

## Release

The `central` Maven profile (in `ai-infrastructure-module/pom.xml`) GPG-signs all artifacts and
uploads them through the `central-publishing-maven-plugin`. The `release` profile attaches the
`-sources.jar` and `-javadoc.jar` Central requires.

Tag and create a GitHub Release; the release workflow publishes automatically:

```bash
git tag ai-fabric-framework-v0.3.0
git push origin ai-fabric-framework-v0.3.0
```

Then create a GitHub Release from the tag. The workflow runs:

```bash
mvn -B -V -f ai-infrastructure-module/pom.xml -Prelease,central deploy
```

With `autoPublish` enabled, the deployment is validated and released to Central without a manual
portal step.

## Boundary

This release is the framework only. It must not include private product code, deployment
credentials, customer configuration, or private operating context.
