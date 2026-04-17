#!/bin/bash
# Test script using kompile-cli.

SCRIPT_PATH="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
mkdir -p "$SCRIPT_PATH/../jars"
JAR_PATH="$SCRIPT_PATH/../jars/KompileCli.jar"

if [ ! -f "$JAR_PATH" ]; then
  COURSIER_PATH="$SCRIPT_PATH/../jars/coursier"
  if [ ! -f "$COURSIER_PATH" ]; then
    echo "Downloading coursier..."
    curl -fLo "$COURSIER_PATH" "https://github.com/coursier/launchers/raw/master/coursier"
    chmod +x "$COURSIER_PATH"
  fi

  echo "Fetching kompile-cli:kompile-cli:0.0.2 from https://kotlin.directory/..."
  CLASSPATH=$("$COURSIER_PATH" fetch --repository https://kotlin.directory/ --repository central kompile-cli:kompile-cli:0.0.2 --classpath)

  cat > "$JAR_PATH" <<'LAUNCHER_EOF'
#!/bin/bash
LAUNCHER_EOF
  echo "CLASSPATH='$CLASSPATH'" >> "$JAR_PATH"
  cat >> "$JAR_PATH" <<'LAUNCHER_EOF'
exec java $JAVA_OPTS -cp "$CLASSPATH" kompile.cli.CliKt "$@"
LAUNCHER_EOF
  chmod +x "$JAR_PATH"
fi

JAVA_PROXY_OPTS=""
if [ -n "$HTTP_PROXY" ]; then
  proxy_host=$(echo "$HTTP_PROXY" | sed -E 's#^[^/]*//([^/:@]+).*#\1#')
  proxy_port=$(echo "$HTTP_PROXY" | sed -nE 's#.*:([0-9]+).*#\1#p')
  JAVA_PROXY_OPTS="-Dhttp.proxyHost=$proxy_host -Dhttps.proxyHost=$proxy_host"
  if [ -n "$proxy_port" ]; then
    JAVA_PROXY_OPTS="$JAVA_PROXY_OPTS -Dhttp.proxyPort=$proxy_port -Dhttps.proxyPort=$proxy_port"
  else
    JAVA_PROXY_OPTS="$JAVA_PROXY_OPTS -Dhttp.proxyPort=80 -Dhttps.proxyPort=443"
  fi
fi

REPO_PATH=$(cd "$SCRIPT_PATH/.." && pwd)
CACHE_PATH="$HOME/.aibuildcaches/$(echo "$REPO_PATH" | sed 's|/|_|g')"
JAVA_OPTS="$JAVA_PROXY_OPTS" "$JAR_PATH" --cache-location "$CACHE_PATH" "$@"
