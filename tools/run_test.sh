#!/bin/bash
# ============================================================
#  One-click test of the MQTT + UDP video links (headless auto-verification)
#
#  MQTT:  broker + mqtt_test_sender.py (128x128.264, H.264)
#  UDP:   udp_test_sender.py (test_sample.hevc, HEVC)
#  RX:    VideoTestReceiver (headless decode, prints decoded frame count)
#
#  Usage:  ./tools/run_test.sh
#  Optional:  ./tools/run_test.sh --mqtt  test MQTT only
#             ./tools/run_test.sh --udp   test UDP only
# ============================================================
set -u

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

# Read ports from config.json
PORT=$(python -c "import json;print(json.load(open('config.json'))['broker']['port'])" 2>/dev/null || echo 11883)
UDP_PORT=$(python -c "import json;print(json.load(open('config.json'))['udp']['port'])" 2>/dev/null || echo 3334)

DO_MQTT=1; DO_UDP=1
for a in "$@"; do
  [ "$a" = "--mqtt" ] && DO_UDP=0
  [ "$a" = "--udp" ] && DO_MQTT=0
done

echo "==============================================="
echo "  一键测试 MQTT + UDP 视频链路"
echo "==============================================="
echo "  Broker 端口: $PORT    UDP 端口: $UDP_PORT"
echo "  测 MQTT: $([ $DO_MQTT = 1 ] && echo 是 || echo 否)   测 UDP: $([ $DO_UDP = 1 ] && echo 是 || echo 否)"
echo "==============================================="

echo "[1/5] 编译项目 ..."
mvn -q compile || { echo "❌ 编译失败"; exit 1; }

echo "[2/5] 生成依赖 classpath ..."
mvn -q dependency:build-classpath -Dmdep.outputFile=/tmp/rt_cp.txt || { echo "❌ classpath 失败"; exit 1; }
# Windows uses ';' as the classpath separator, Linux/Mac use ':'
SEP=":"
[ "$(uname -s 2>/dev/null | grep -iE 'mingw|msys|cygwin')" ] && SEP=";"
CP="target/classes${SEP}$(cat /tmp/rt_cp.txt)"

echo "[3/5] 启动本地 broker ..."
python tools/mqtt_broker.py --port "$PORT" > /tmp/rt_broker.log 2>&1 &
BROKER_PID=$!
sleep 2

echo "[4/5] 启动无头接收器 ..."
java -cp "$CP" com.mqttclient.util.VideoTestReceiver 14 > /tmp/rt_recv.log 2>&1 &
RECV_PID=$!
sleep 3

# ---- Send MQTT ----
if [ $DO_MQTT = 1 ]; then
  echo "    · MQTT 发送 128x128.264 (H.264) ..."
  python tools/mqtt_test_sender.py 128x128.264 --port "$PORT" > /dev/null 2>&1
  sleep 1
fi

# ---- Send UDP ----
if [ $DO_UDP = 1 ]; then
  echo "    · UDP 发送 test_sample.hevc (HEVC) ..."
  python tools/udp_test_sender.py --file test_sample.hevc --host 127.0.0.1 --port "$UDP_PORT" > /dev/null 2>&1
fi

echo "[5/5] 等待接收器统计 ..."
wait $RECV_PID 2>/dev/null || true
kill $BROKER_PID 2>/dev/null

echo ""
echo "==============================================="
echo "  测试结果"
echo "==============================================="
grep -aE "RESULT_MQTT|RESULT_UDP" /tmp/rt_recv.log

MQTT_DEC=$(grep -a "RESULT_MQTT" /tmp/rt_recv.log | grep -oE "decoded=[0-9]+" | cut -d= -f2 | head -1)
UDP_DEC=$(grep -a "RESULT_UDP" /tmp/rt_recv.log | grep -oE "decoded=[0-9]+" | cut -d= -f2 | head -1)
[ -z "$MQTT_DEC" ] && MQTT_DEC=0
[ -z "$UDP_DEC" ] && UDP_DEC=0

echo ""
if [ $DO_MQTT = 1 ]; then
  if [ "$MQTT_DEC" -gt 0 ]; then
    echo "✅ MQTT (H.264): 解码 $MQTT_DEC 帧  — 通过"
  else
    echo "❌ MQTT (H.264): 未解码到画面 — 失败"
  fi
fi
if [ $DO_UDP = 1 ]; then
  if [ "$UDP_DEC" -gt 0 ]; then
    echo "✅ UDP (HEVC): 解码 $UDP_DEC 帧  — 通过"
  else
    echo "❌ UDP (HEVC): 未解码到画面 — 失败"
  fi
fi
echo "==============================================="
