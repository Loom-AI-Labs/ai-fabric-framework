# GitHub Packages Release Guide

This guide releases AI Fabric Framework as Maven packages in GitHub Packages and as a framework-only source archive attached to a GitHub Release.

## Release Target

- Maven registry: `https://maven.pkg.github.com/loom-ai-labs/ai-fabric-framework`
- Maven group: `com.ai.fabric`
- BOM artifact: `ai-fabric-bom`
- Source asset name: `ai-fabric-framework-source-<version>.tar.gz`
- Release tag format: `ai-fabric-framework-v<version>`

Recommended first version:

```text
0.1.0-preview
```

## Verify Before Release

```bash
mvn -B -V --no-transfer-progress -f ai-infrastructure-module/pom.xml validate
mvn -B -V --no-transfer-progress -f ai-infrastructure-module/pom.xml -DskipTests compile
```

## Tag and Release

```bash
git status --short
git tag ai-fabric-framework-v0.1.0-preview
git push origin main
git push origin ai-fabric-framework-v0.1.0-preview
```

Create a GitHub Release from the tag. The release workflow publishes Maven artifacts and uploads a framework-only source archive with a SHA-256 checksum.

## Consume From Maven

Consumers need GitHub Packages access.

Example `~/.m2/settings.xml`:

```xml
<settings>
  <servers>
    <server>
      <id>github</id>
      <username>${env.GITHUB_USERNAME}</username>
      <password>${env.GITHUB_PACKAGES_TOKEN}</password>
    </server>
  </servers>
</settings>
```

Consumer repository and BOM:

```xml
<repositories>
  <repository>
    <id>github</id>
    <url>https://maven.pkg.github.com/loom-ai-labs/ai-fabric-framework</url>
  </repository>
</repositories>

<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>com.ai.fabric</groupId>
      <artifactId>ai-fabric-bom</artifactId>
      <version>0.1.0-preview</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>
```

## Boundary

This release is the framework only. It must not include private product code, deployment credentials, customer configuration, or private operating context.
