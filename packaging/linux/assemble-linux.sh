#!/usr/bin/env bash
# 把双平台 fat jar 组装成 Linux x86_64 分发包（tar.gz）。
# 用法: bash packaging/linux/assemble-linux.sh [version]   (默认 1.0.0)
# 前置: 先运行  mvn clean package  生成 target/mqtt-h264-client.jar
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
VERSION="${1:-1.0.0}"
DIST_NAME="mqtt-h264-client-${VERSION}-linux-x86_64"
STAGE="$ROOT/target/dist/$DIST_NAME"

JAR="$ROOT/target/mqtt-h264-client.jar"
if [[ ! -f "$JAR" ]]; then
  echo "[ERROR] $JAR 不存在。请先执行: mvn clean package" >&2
  exit 1
fi

rm -rf "$STAGE"
mkdir -p "$STAGE"

cp "$JAR" "$STAGE/mqtt-h264-client.jar"
cp "$ROOT/config.json" "$STAGE/config.json"
cp "$ROOT/packaging/linux/run-client.sh" "$STAGE/run-client.sh"
chmod +x "$STAGE/run-client.sh"

tar -C "$ROOT/target/dist" -czf "$ROOT/target/${DIST_NAME}.tar.gz" "$DIST_NAME"
echo "已生成: $ROOT/target/${DIST_NAME}.tar.gz"
