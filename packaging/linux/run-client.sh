#!/usr/bin/env bash
# MQTT H.264 视频接收端 — Linux 启动脚本。
# Run from anywhere: changes cwd to the script dir so ./config.json is picked up.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

JAVA=""
if [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/java" ]]; then
  JAVA="$JAVA_HOME/bin/java"
elif command -v java >/dev/null 2>&1; then
  JAVA="$(command -v java)"
else
  echo "未检测到 Java 17+，请先安装 JDK 17/21（例如: sudo apt install openjdk-17-jdk）。" >&2
  echo "No JDK 17+ found. Install openjdk-17/21-jdk first (e.g. sudo apt install openjdk-17-jdk)." >&2
  exit 1
fi

# Swing 需要 X/Wayland 显示环境；无 DISPLAY 的 headless 机器会在此给出明确报错。
exec "$JAVA" -Dfile.encoding=UTF-8 -jar "$SCRIPT_DIR/mqtt-h264-client.jar" "$@"
