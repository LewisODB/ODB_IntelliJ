#!/bin/sh
set -eu

script_directory=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
project_directory=$(dirname -- "$script_directory")
odb_java_home=${ODB_GRADLE_JAVA_HOME:-}
gradle_java_home=${GRADLE_JAVA_HOME:-}

if [ -z "$odb_java_home" ] && [ -x /usr/libexec/java_home ]; then
    odb_java_home=$(/usr/libexec/java_home -v 17)
fi
if [ -z "$gradle_java_home" ] && [ -x /usr/libexec/java_home ]; then
    gradle_java_home=$(/usr/libexec/java_home -v 21)
fi
if [ -z "$odb_java_home" ]; then
    echo "Set ODB_GRADLE_JAVA_HOME to JDK 17 or newer." >&2
    exit 2
fi
if [ -z "$gradle_java_home" ]; then
    echo "Set GRADLE_JAVA_HOME to JDK 17 or newer." >&2
    exit 2
fi

(
    cd "$project_directory/LewisOmniscientDebugger"
    JAVA_HOME="$odb_java_home" ./gradlew test \
        --tests 'com.lambda.Debugger.IntegrationLauncherProcessTest.integrationSourceRootsLoadSourceWithoutOpeningTheChooser'
)
(
    cd "$project_directory"
    JAVA_HOME="$gradle_java_home" ./gradlew test \
        --tests 'org.lewisodb.intellij.launch.OdbPreflightTest.testRunnerSeedsSelectedMainSourceRootBeforeProcessCreation'
)
