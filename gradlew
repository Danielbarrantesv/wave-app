#!/bin/sh
# Gradle wrapper script for GitHub Actions and local builds.
# Downloads the correct Gradle version automatically.
DIR="$(cd "$(dirname "$0")" && pwd)"
APP_HOME="$DIR"
exec java -classpath "$APP_HOME/gradle/wrapper/gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain "$@"
