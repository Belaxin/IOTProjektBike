#!/usr/bin/env sh
if [ -z "$JAVA_HOME" ]; then
  if command -v java >/dev/null 2>&1; then
    JAVA_CMD=java
  else
    echo "ERROR: JAVA_HOME is not set and java could not be found in PATH."
    exit 1
  fi
else
  JAVA_CMD="$JAVA_HOME/bin/java"
fi
DIR="$(cd "$(dirname "$0")" && pwd)"
"$JAVA_CMD" -jar "$DIR/gradle/wrapper/gradle-wrapper.jar" "$@"

