# Lewis ODB

An IntelliJ Platform plugin for running an existing Java Application configuration with [Lewis Omniscient Debugger](https://github.com/LewisODB/OmniscientDebugger).

The plugin adds **Run with ODB**. IntelliJ keeps its normal console and process controls. ODB opens in a separate Swing window. The plugin does not modify the project or saved run configuration.

## Quick start

1. Install IntelliJ IDEA build 252 or later and a local JDK 8.
2. Download `Lewis-ODB-1.0.0-signed.zip` and `SHA256SUMS` from the [1.0.0 release](https://github.com/LewisODB/ODB_IntelliJ/releases/tag/v1.0.0).
3. Verify the ZIP against `SHA256SUMS`.
4. Open **Settings > Plugins**, open the settings menu, choose **Install Plugin from Disk**, and select the ZIP. Restart IntelliJ if prompted.
5. Select a local, classpath-based Java Application configuration that uses JDK 8.
6. Open the executor menu beside the run widget and choose **Run with ODB**.

See the [IntelliJ plugin guide](https://omniscientdebugger.github.io/LewisOmniscientDebugger/intellij-plugin/) for configuration, first-run use, and troubleshooting.

## Requirements

- IntelliJ IDEA build 252 or later on macOS, Windows, or Linux
- A local Java Application configuration using JDK 8 and the classpath
- A desktop graphics environment

Version 1 does not support test, Gradle, Maven, remote, compound, Android, WSL, container, JPMS module-path, or target JDK 9 and later configurations. Install version 1 from the GitHub ZIP, not JetBrains Marketplace.

## Development

Use JDK 21 to run Gradle.

Build the plugin distribution:

```shell
./gradlew buildPlugin
```

Run it in a development instance of IntelliJ IDEA:

```shell
./gradlew runIde
```

The generated plugin ZIP is written under `build/distributions/`.
