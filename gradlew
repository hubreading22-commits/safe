#!/bin/sh
# Gradle wrapper script
exec java -cp "$(dirname "$0")/gradle/wrapper/gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain "$@"
