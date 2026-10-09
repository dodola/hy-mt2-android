#!/usr/bin/env bash
# Gradle wrapper with the (possibly dead) global proxy from ~/.gradle/gradle.properties disabled.
exec "$(dirname "$0")/gradlew" --no-daemon \
  -Dhttp.proxyHost= -Dhttps.proxyHost= -Dhttp.proxyPort= -Dhttps.proxyPort= \
  -Dhttp.nonProxyHosts='*' -Dhttps.nonProxyHosts='*' "$@"
