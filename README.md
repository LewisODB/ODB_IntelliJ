# Lewis ODB

An IntelliJ Platform plugin for running an existing Java Application configuration with [Lewis Omniscient Debugger](https://github.com/LewisODB/OmniscientDebugger).

The plugin adds **Run with ODB**. IntelliJ keeps its normal console and process controls. ODB opens in a separate Swing window. The plugin does not modify the project or saved run configuration.

## Status

Under development. **Run with ODB** works in the local development sandbox, including automatic source lookup. The self-contained plugin ZIP carries the audited ODB runtime and passes the supported IntelliJ compatibility matrix; cross-platform qualification remains incomplete.

Version 1 supports IntelliJ IDEA builds 252 through 261.* on macOS, Windows, and Linux. Target applications must use a local JDK 8 and the classpath.

Version 1 excludes test, Gradle task, Maven task, remote, compound, and Android run configurations. It also excludes WSL, containers, JPMS module-path applications, and target JDK 9 or newer.

## Installation

Download the plugin ZIP from [GitHub Releases](https://github.com/LewisODB/ODB_IntelliJ/releases). In IntelliJ IDEA, choose **Settings > Plugins > Install Plugin from Disk**, then select the ZIP. Marketplace installation is not available for version 1.

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
