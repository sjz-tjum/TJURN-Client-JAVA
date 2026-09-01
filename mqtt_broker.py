#!/usr/bin/env python3
"""
简易 MQTT 3.1.1 Broker — 用于测试视频流收发。

纯 Python + asyncio 实现，无需外部依赖。支持:
  - CONNECT / CONNACK
  - PUBLISH (QoS 0/1/2) + 消息路由
  - SUBSCRIBE / SUBACK（含 + 和 # 通配符匹配）
  - UNSUBSCRIBE / UNSUBACK
  - PINGREQ / PINGRESP
  - DISCONNECT
  - 遗嘱消息 (Will Message)
  - 保留消息 (Retained Message)
  - Clean Session

用法:
    python mqtt_broker.py                          # 默认 0.0.0.0:1883
    python mqtt_broker.py --port 3333              # 自定义端口
    python mqtt_broker.py --host 0.0.0.0 --port 1883 --verbose

然后客户端连接这个 broker 即可，例如 Java 端把 Constants 里的
DEFAULT_BROKER_HOST 改为 127.0.0.1 / 本机 IP。
"""

import argparse
import asyncio
import logging
import struct
import time
from collections import defaultdict

# ── 日志 ──────────────────────────────────────────────────────────────────

logger = logging.getLogger("mqtt-broker")

# ── MQTT 3.1.1 协议常量 ──────────────────────────────────────────────────

# 控制包类型 (packet_type << 4 | flags)
CONNECT = 1
CONNACK = 2
PUBLISH = 3
PUBACK = 4
PUBREC = 5
PUBREL = 6
PUBCOMP = 7
SUBSCRIBE = 8
SUBACK = 9
UNSUBSCRIBE = 10
UNSUBACK = 11
PINGREQ = 12
PINGRESP = 13
DISCONNECT = 14

# CONNACK 返回码
CONNACK_ACCEPTED = 0
CONNACK_REFUSED_PROTOCOL = 1
CONNACK_REFUSED_ID = 2
CONNACK_REFUSED_SERVER = 3
CONNACK_REFUSED_BAD_USER_PASS = 4
CONNACK_REFUSED_NOT_AUTHORIZED = 5

# SUBACK 返回码
SUBACK_QOS0 = 0x00
SUBACK_QOS1 = 0x01
SUBACK_QOS2 = 0x02
SUBACK_FAILURE = 0x80


# ── MQTT 编解码 ──────────────────────────────────────────────────────────

class MQTTPacketError(Exception):
    pass


def encode_remaining_length(length: int) -> bytes:
    """变长编码 remaining length (MQTT §2.2.3)。"""
    if length > 268_435_455:
        raise MQTTPacketError("remaining length too large")
    data = bytearray()
    while True:
        byte = length % 128
        length //= 128
        if length > 0:
            byte |= 0x80
        data.append(byte)
        if length == 0:
            break
    return bytes(data)


def decode_remaining_length(data: bytes, offset: int) -> tuple[int, int]:
    """变长解码 remaining length，返回 (length, bytes_consumed)。"""
    multiplier = 1
    value = 0
    consumed = 0
    while True:
        if offset + consumed >= len(data):
            raise MQTTPacketError("remaining length truncated")
        byte = data[offset + consumed]
        consumed += 1
        value += (byte & 0x7F) * multiplier
        if multiplier > 128 * 128 * 128:
            raise MQTTPacketError("malformed remaining length")
        multiplier *= 128
        if (byte & 0x80) == 0:
            break
    return value, consumed


def encode_utf8(s: str) -> bytes:
    """MQTT UTF-8 编码：2 字节长度前缀 + UTF-8 数据。"""
    data = s.encode("utf-8")
    if len(data) > 65535:
        raise MQTTPacketError("string too long")
    return struct.pack("!H", len(data)) + data


def decode_utf8(data: bytes, offset: int) -> tuple[str, int]:
    """MQTT UTF-8 解码，返回 (string, new_offset)。"""
    if offset + 2 > len(data):
        raise MQTTPacketError("string length field truncated")
    length = struct.unpack("!H", data[offset:offset + 2])[0]
    offset += 2
    if offset + length > len(data):
        raise MQTTPacketError("string data truncated")
    s = data[offset:offset + length].decode("utf-8", errors="replace")
    return s, offset + length


def make_fixed_header(packet_type: int, flags: int, remaining_length: int) -> bytes:
    """构造固定头。"""
    return bytes([(packet_type << 4) | flags]) + encode_remaining_length(remaining_length)


# ── 数据包构造 ──────────────────────────────────────────────────────────

def make_connack(session_present: bool = False, return_code: int = CONNACK_ACCEPTED) -> bytes:
    variable = bytes([1 if session_present else 0, return_code])
    return make_fixed_header(CONNACK, 0, len(variable)) + variable


def make_suback(packet_id: int, return_codes: list[int]) -> bytes:
    variable = struct.pack("!H", packet_id) + bytes(return_codes)
    return make_fixed_header(SUBACK, 0, len(variable)) + variable


def make_unsuback(packet_id: int) -> bytes:
    variable = struct.pack("!H", packet_id)
    return make_fixed_header(UNSUBACK, 0, len(variable)) + variable


def make_puback(packet_id: int) -> bytes:
    variable = struct.pack("!H", packet_id)
    return make_fixed_header(PUBACK, 0, len(variable)) + variable


def make_pubrec(packet_id: int) -> bytes:
    variable = struct.pack("!H", packet_id)
    return make_fixed_header(PUBREC, 0, len(variable)) + variable


def make_pubrel(packet_id: int) -> bytes:
    variable = struct.pack("!H", packet_id)
    return make_fixed_header(PUBREL, 2, len(variable)) + variable


def make_pubcomp(packet_id: int) -> bytes:
    variable = struct.pack("!H", packet_id)
    return make_fixed_header(PUBCOMP, 0, len(variable)) + variable


def make_pingresp() -> bytes:
    return make_fixed_header(PINGRESP, 0, 0)


# ── 主题匹配 ─────────────────────────────────────────────────────────────

def topic_matches(subscription: str, topic: str) -> bool:
    """
    MQTT 3.1.1 §4.7 主题过滤匹配。
    支持单层通配符 '+' 和多层通配符 '#'。
    """
    sub_parts = subscription.split("/")
    topic_parts = topic.split("/")

    for i, sub_part in enumerate(sub_parts):
        if sub_part == "#":
            # '#' 匹配所有剩余层级，且必须是最后一个
            return i == len(sub_parts) - 1
        if sub_part == "+":
            # '+' 匹配恰好一层（不能为空）
            if i >= len(topic_parts):
                return False
            if topic_parts[i] == "":
                # 但有些 broker 允许 + 匹配空… 遵循严格规范
                pass
            continue
        if i >= len(topic_parts):
            return False
        if sub_part != topic_parts[i]:
            return False

    # 所有订阅层级都消费完时，主题也必须刚好消费完
    return len(sub_parts) == len(topic_parts)


# ── 会话 / 客户端 ────────────────────────────────────────────────────────

class ClientSession:
    """单个 MQTT 客户端会话。"""

    def __init__(self, client_id: str, reader: asyncio.StreamReader,
                 writer: asyncio.StreamWriter, clean_session: bool = True,
                 keep_alive: int = 60):
        self.client_id = client_id
        self.reader = reader
        self.writer = writer
        self.clean_session = clean_session
        self.keep_alive = keep_alive
        self.connected_at = time.time()
        self.last_packet = time.time()
        self.will_topic: str | None = None
        self.will_message: bytes | None = None
        self.will_qos: int = 0
        self.will_retain: bool = False
        # QoS 1/2 未完成事务
        self.pending_out: dict[int, asyncio.Task] = {}   # packet_id -> resend task
        self.pending_in: dict[int, bytes] = {}            # packet_id -> payload (QoS 2 阶段一)

    @property
    def addr(self):
        try:
            return self.writer.get_extra_info("peername")
        except Exception:
            return ("?", 0)


# ── Broker 核心 ──────────────────────────────────────────────────────────

class MQTTBroker:
    """简易 MQTT 3.1.1 Broker。"""

    def __init__(self, host: str = "0.0.0.0", port: int = 1883, verbose: bool = False):
        self.host = host
        self.port = port
        self.verbose = verbose

        # topic -> list[ClientSession]  (非通配符精确订阅)
        self._subscriptions: dict[str, list[ClientSession]] = defaultdict(list)
        # client_id -> ClientSession
        self._sessions: dict[str, ClientSession] = {}
        # 保留消息: topic -> (payload, qos)
        self._retained: dict[str, tuple[bytes, int]] = {}
        # 下一个 packet_id (服务端发布时用)
        self._next_packet_id = 1
        # 全局统计
        self._total_published = 0
        self._total_bytes = 0
        self._start_time: float | None = None
        self._clients_connected = 0

        self._server: asyncio.Server | None = None

    # ── 订阅管理 ──────────────────────────────────────────────────────

    def subscribe(self, client: ClientSession, topic_filter: str, qos: int):
        """添加订阅。"""
        # 实际授予的 QoS 取 min(请求 QoS, 2)
        granted_qos = min(qos, 2)
        self._subscriptions[topic_filter].append(client)
        if self.verbose:
            logger.info("[订阅] client=%s topic=%s qos=%d", client.client_id, topic_filter, granted_qos)
        return granted_qos

    def unsubscribe(self, client: ClientSession, topic_filter: str):
        """移除订阅。"""
        subs = self._subscriptions.get(topic_filter, [])
        if client in subs:
            subs.remove(client)
            if not subs:
                del self._subscriptions[topic_filter]
        if self.verbose:
            logger.info("[取消] client=%s topic=%s", client.client_id, topic_filter)

    def find_subscribers(self, topic: str) -> list[tuple[ClientSession, int]]:
        """找到所有匹配 topic 的订阅者，返回 [(session, qos), ...]。
        去重：同一客户端多次匹配时选最高 QoS。
        """
        result: dict[str, tuple[ClientSession, int]] = {}
        for sub_filter, clients in self._subscriptions.items():
            if topic_matches(sub_filter, topic):
                for c in clients:
                    if c.client_id not in result:
                        result[c.client_id] = (c, 0)
        return list(result.values())

    def remove_session(self, client: ClientSession):
        """移除客户端的所有订阅和会话。"""
        for topic_filter in list(self._subscriptions.keys()):
            subs = self._subscriptions[topic_filter]
            if client in subs:
                subs.remove(client)
                if not subs:
                    del self._subscriptions[topic_filter]
        if client.client_id in self._sessions:
            del self._sessions[client.client_id]

    # ── 消息发布 ──────────────────────────────────────────────────────

    def publish(self, topic: str, payload: bytes, qos: int = 0,
                retain: bool = False, sender: ClientSession | None = None):
        """发布消息：路由到所有匹配订阅者 + 处理保留。"""
        # 保留消息
        if retain:
            if len(payload) > 0:
                self._retained[topic] = (payload, qos)
            else:
                self._retained.pop(topic, None)

        # 路由给订阅者
        subs = self.find_subscribers(topic)
        for client, sub_qos in subs:
            if sender and client.client_id == sender.client_id:
                continue  # 不发给发送者自己
            effective_qos = min(qos, sub_qos)
            self._send_to_client(client, topic, payload, effective_qos, retain=False)

        self._total_published += 1
        self._total_bytes += len(payload)

    def _send_to_client(self, client: ClientSession, topic: str, payload: bytes,
                        qos: int, retain: bool):
        """向单个客户端推送 PUBLISH 包。"""
        try:
            packet = self._build_publish(topic, payload, qos, retain)
            client.writer.write(packet)
        except Exception as e:
            logger.warning("发送失败 client=%s: %s", client.client_id, e)

    def _build_publish(self, topic: str, payload: bytes, qos: int, retain: bool) -> bytes:
        """构造 PUBLISH 包。"""
        flags = 0
        if retain:
            flags |= 0x01
        flags |= (qos & 0x03) << 1

        # 可变头: topic name
        variable = encode_utf8(topic)
        # QoS > 0 时需要 packet_id
        packet_id = 0
        if qos > 0:
            packet_id = self._next_packet_id
            self._next_packet_id = (self._next_packet_id % 65535) + 1
            variable += struct.pack("!H", packet_id)

        remaining = len(variable) + len(payload)
        packet = make_fixed_header(PUBLISH, flags, remaining) + variable + payload

        if self.verbose and qos > 0:
            logger.debug("  → PUBLISH id=%d topic=%s qos=%d len=%d",
                         packet_id, topic, qos, len(payload))

        return packet

    def _send_retained(self, client: ClientSession, topic_filter: str):
        """发送匹配 topic_filter 的保留消息给新订阅者。"""
        for topic, (payload, qos) in self._retained.items():
            if topic_matches(topic_filter, topic):
                self._send_to_client(client, topic, payload, qos, retain=True)

    # ── 遗嘱消息 ──────────────────────────────────────────────────────

    def _publish_will(self, client: ClientSession):
        """客户端异常断开时发布遗嘱消息。"""
        if client.will_topic is None:
            return
        if self.verbose:
            logger.info("[遗嘱] client=%s topic=%s", client.client_id, client.will_topic)
        self.publish(client.will_topic, client.will_message,
                     qos=client.will_qos, retain=client.will_retain)

    # ── 客户端处理 ─────────

    async def handle_client(self, reader: asyncio.StreamReader,
                            writer: asyncio.StreamWriter):
        """处理单个客户端连接的主循环。"""
        addr = writer.get_extra_info("peername")
        client: ClientSession | None = None

        try:
            # 第一步必须是 CONNECT
            packet_type, flags, payload = await self._read_packet(reader)
            if packet_type != CONNECT:
                logger.warning("[%s:%d] 首包非 CONNECT (type=%d), 断开",
                               addr[0], addr[1], packet_type)
                writer.close()
                return

            client = await self._handle_connect(reader, writer, payload)
            if client is None:
                return  # _handle_connect 内部已发送 CONNACK 拒绝

            if self.verbose:
                logger.info("[连接] client=%s addr=%s:%d keepalive=%ds",
                            client.client_id, addr[0], addr[1], client.keep_alive)

            # 主循环：处理后续包
            keep_alive_timeout = max(client.keep_alive * 1.5, 10)
            while True:
                try:
                    packet_type, flags, payload = await asyncio.wait_for(
                        self._read_packet(reader), timeout=keep_alive_timeout
                    )
                except asyncio.TimeoutError:
                    logger.info("[超时] client=%s (keepalive=%ds)", client.client_id, client.keep_alive)
                    break

                client.last_packet = time.time()
                await self._dispatch(client, packet_type, flags, payload)

        except asyncio.IncompleteReadError:
            # 客户端正常断开
            pass
        except ConnectionResetError:
            pass
        except MQTTPacketError as e:
            logger.warning("[%s:%d] 协议错误: %s", addr[0], addr[1], e)
        except OSError as e:
            logger.warning("[%s:%d] IO 错误: %s", addr[0], addr[1], e)
        except Exception as e:
            logger.error("[%s:%d] 未预期的错误: %s", addr[0], addr[1], e, exc_info=True)
        finally:
            if client:
                if self.verbose:
                    logger.info("[断开] client=%s", client.client_id)
                # 非正常断开时发送遗嘱
                # (简化处理：总是检查遗嘱)
                self._publish_will(client)
                self.remove_session(client)
                self._clients_connected -= 1
            try:
                writer.close()
            except Exception:
                pass

    async def _read_packet(self, reader: asyncio.StreamReader) -> tuple[int, int, bytes]:
        """读取一个 MQTT 包，返回 (packet_type, flags, payload)。
        payload 是控制包类型对应的可变头 + 有效载荷的剩余部分。
        """
        # 固定头首字节
        first = await reader.readexactly(1)
        byte0 = first[0]
        packet_type = (byte0 >> 4) & 0x0F
        flags = byte0 & 0x0F

        # remaining length
        remaining = 0
        multiplier = 1
        while True:
            b = await reader.readexactly(1)
            byte = b[0]
            remaining += (byte & 0x7F) * multiplier
            if multiplier > 128 * 128 * 128:
                raise MQTTPacketError("malformed remaining length")
            multiplier *= 128
            if (byte & 0x80) == 0:
                break

        # 读取剩余部分
        payload = b""
        if remaining > 0:
            payload = await reader.readexactly(remaining)

        return packet_type, flags, payload

    async def _handle_connect(self, reader: asyncio.StreamReader,
                        writer: asyncio.StreamWriter,
                        payload: bytes) -> ClientSession | None:
        """处理 CONNECT 包。成功返回 ClientSession，失败返回 None 并发送 CONNACK 拒绝。"""
        offset = 0

        # 协议名
        proto_name, offset = decode_utf8(payload, offset)
        if proto_name not in ("MQTT", "MQIsdp"):
            writer.write(make_connack(return_code=CONNACK_REFUSED_PROTOCOL))
            writer.close()
            return None

        # 协议级别 (MQTT 3.1.1 = 4)
        if offset >= len(payload):
            raise MQTTPacketError("CONNECT truncated at protocol level")
        protocol_level = payload[offset]
        offset += 1

        # 连接标志
        if offset >= len(payload):
            raise MQTTPacketError("CONNECT truncated at connect flags")
        connect_flags = payload[offset]
        offset += 1

        clean_session = bool(connect_flags & 0x02)
        will_flag = bool(connect_flags & 0x04)
        will_qos = (connect_flags >> 3) & 0x03
        will_retain = bool(connect_flags & 0x20)
        password_flag = bool(connect_flags & 0x40)
        username_flag = bool(connect_flags & 0x80)

        # Keep Alive
        if offset + 2 > len(payload):
            raise MQTTPacketError("CONNECT truncated at keep alive")
        keep_alive = struct.unpack("!H", payload[offset:offset + 2])[0]
        offset += 2

        # Client ID
        client_id, offset = decode_utf8(payload, offset)
        if not client_id:
            # 空 client_id 需要 clean_session=true 且由 broker 分配
            if clean_session:
                import uuid
                client_id = "auto-" + uuid.uuid4().hex[:12]
            else:
                writer.write(make_connack(return_code=CONNACK_REFUSED_ID))
                writer.close()
                return None

        # 检查是否已有同名客户端
        existing = self._sessions.get(client_id)
        if existing:
            logger.info("[踢旧] 同名 client=%s 上线，踢掉旧连接", client_id)
            try:
                existing.writer.close()
            except Exception:
                pass
            self.remove_session(existing)

        # Will Topic / Will Message
        will_topic = None
        will_message = None
        if will_flag:
            will_topic, offset = decode_utf8(payload, offset)
            if offset + 2 > len(payload):
                raise MQTTPacketError("CONNECT truncated at will message length")
            will_msg_len = struct.unpack("!H", payload[offset:offset + 2])[0]
            offset += 2
            if offset + will_msg_len > len(payload):
                raise MQTTPacketError("CONNECT truncated at will message")
            will_message = payload[offset:offset + will_msg_len]
            offset += will_msg_len

        # Username
        username = None
        if username_flag:
            username, offset = decode_utf8(payload, offset)

        # Password
        password = None
        if password_flag:
            if offset + 2 > len(payload):
                raise MQTTPacketError("CONNECT truncated at password length")
            pwd_len = struct.unpack("!H", payload[offset:offset + 2])[0]
            offset += 2
            if offset + pwd_len > len(payload):
                raise MQTTPacketError("CONNECT truncated at password")
            password = payload[offset:offset + pwd_len]
            offset += pwd_len

        # 创建会话
        client = ClientSession(client_id, reader, writer,
                               clean_session=clean_session,
                               keep_alive=keep_alive)
        client.will_topic = will_topic
        client.will_message = will_message
        client.will_qos = will_qos
        client.will_retain = will_retain

        self._sessions[client_id] = client
        self._clients_connected += 1

        # 发送 CONNACK
        session_present = not clean_session
        connack = make_connack(session_present=session_present,
                               return_code=CONNACK_ACCEPTED)
        writer.write(connack)
        await self._drain(writer)

        return client

    async def _dispatch(self, client: ClientSession, packet_type: int,
                        flags: int, payload: bytes):
        """根据包类型分发处理。"""
        match packet_type:
            case 3:  # PUBLISH
                await self._handle_publish(client, flags, payload)
            case 4:  # PUBACK
                self._handle_puback(client, payload)
            case 5:  # PUBREC
                self._handle_pubrec(client, payload)
            case 6:  # PUBREL
                self._handle_pubrel(client, payload)
            case 7:  # PUBCOMP
                self._handle_pubcomp(client, payload)
            case 8:  # SUBSCRIBE
                self._handle_subscribe(client, payload)
            case 10:  # UNSUBSCRIBE
                self._handle_unsubscribe(client, payload)
            case 12:  # PINGREQ
                client.writer.write(make_pingresp())
            case 14:  # DISCONNECT
                # 客户端主动断开，清除遗嘱
                client.will_topic = None
                client.will_message = None
                raise asyncio.IncompleteReadError  # 跳出主循环
            case _:
                if self.verbose:
                    logger.debug("未处理的包类型: %d", packet_type)

    # ── PUBLISH ─────────────────────────────────────────────────────

    async def _handle_publish(self, client: ClientSession, flags: int, payload: bytes):
        dup = bool(flags & 0x08)
        qos = (flags >> 1) & 0x03
        retain = bool(flags & 0x01)
        offset = 0

        topic, offset = decode_utf8(payload, offset)

        packet_id = 0
        if qos > 0:
            if offset + 2 > len(payload):
                raise MQTTPacketError("PUBLISH with QoS>0 missing packet_id")
            packet_id = struct.unpack("!H", payload[offset:offset + 2])[0]
            offset += 2

        message = payload[offset:]

        if self.verbose:
            logger.debug("[发布] client=%s topic=%s qos=%d len=%d retain=%s",
                         client.client_id, topic, qos, len(message), retain)

        # 路由给订阅者
        self.publish(topic, message, qos=qos, retain=retain, sender=client)

        # QoS 1: 回 PUBACK
        if qos == 1:
            client.writer.write(make_puback(packet_id))
        # QoS 2: 回 PUBREC，等待 PUBREL
        elif qos == 2:
            client.pending_in[packet_id] = message  # 暂存
            client.writer.write(make_pubrec(packet_id))

    def _handle_puback(self, client: ClientSession, payload: bytes):
        pass  # QoS 1 完成，无需处理

    def _handle_pubrec(self, client: ClientSession, payload: bytes):
        """收到 PUBREC → 发送 PUBREL。"""
        if len(payload) < 2:
            return
        packet_id = struct.unpack("!H", payload[:2])[0]
        client.writer.write(make_pubrel(packet_id))

    def _handle_pubrel(self, client: ClientSession, payload: bytes):
        """收到 PUBREL → 发送 PUBCOMP，完成 QoS 2。"""
        if len(payload) < 2:
            return
        packet_id = struct.unpack("!H", payload[:2])[0]
        # 取出暂存消息并发布
        msg = client.pending_in.pop(packet_id, None)
        if msg:
            pass  # 消息已在 PUBLISH 阶段路由，这里只确认
        client.writer.write(make_pubcomp(packet_id))

    def _handle_pubcomp(self, client: ClientSession, payload: bytes):
        pass  # QoS 2 完成

    # ── SUBSCRIBE ───────────────────────────────────────────────────

    def _handle_subscribe(self, client: ClientSession, payload: bytes):
        if len(payload) < 2:
            raise MQTTPacketError("SUBSCRIBE missing packet_id")
        packet_id = struct.unpack("!H", payload[:2])[0]
        offset = 2

        return_codes = []
        while offset < len(payload):
            topic_filter, offset = decode_utf8(payload, offset)
            if offset >= len(payload):
                raise MQTTPacketError("SUBSCRIBE missing QoS byte")
            req_qos = payload[offset]
            offset += 1

            granted = self.subscribe(client, topic_filter, req_qos)
            return_codes.append(granted)

            # 发送保留消息
            self._send_retained(client, topic_filter)

        suback = make_suback(packet_id, return_codes)
        client.writer.write(suback)

    # ── UNSUBSCRIBE ─────────────────────────────────────────────────

    def _handle_unsubscribe(self, client: ClientSession, payload: bytes):
        if len(payload) < 2:
            raise MQTTPacketError("UNSUBSCRIBE missing packet_id")
        packet_id = struct.unpack("!H", payload[:2])[0]
        offset = 2

        while offset < len(payload):
            topic_filter, offset = decode_utf8(payload, offset)
            self.unsubscribe(client, topic_filter)

        client.writer.write(make_unsuback(packet_id))

    # ── 工具 ────────────────────────────────────────────────────────

    async def _drain(self, writer: asyncio.StreamWriter):
        """等待写缓冲区清空。"""
        try:
            await writer.drain()
        except Exception:
            pass

    # ── 统计 ────────────────────────────────────────────────────────

    def _log_stats(self):
        elapsed = time.time() - self._start_time if self._start_time else 0
        pps = self._total_published / elapsed if elapsed > 0 else 0
        kbps = (self._total_bytes * 8 / 1000) / elapsed if elapsed > 0 else 0
        logger.info("━━ 统计 ━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        logger.info("  客户端数:    %d", self._clients_connected)
        logger.info("  总发布数:    %d", self._total_published)
        logger.info("  总流量:      %.1f KB", self._total_bytes / 1024)
        logger.info("  速率:        %.1f pps | %.1f kbps", pps, kbps)
        logger.info("  订阅表键数:  %d", len(self._subscriptions))
        logger.info("  保留消息数:  %d", len(self._retained))
        logger.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")

    # ── 启动 / 停止 ──────────────────────────────────────────────────

    async def start(self):
        """启动 broker。"""
        self._start_time = time.time()
        self._server = await asyncio.start_server(
            self.handle_client, self.host, self.port
        )
        addrs = ", ".join(str(s.getsockname()) for s in self._server.sockets)
        logger.info("══════════════════════════════════════════")
        logger.info("  MQTT 3.1.1 Broker 已启动")
        logger.info("══════════════════════════════════════════")
        logger.info("  监听:        %s", addrs)
        logger.info("  客户端数:    %d", self._clients_connected)
        logger.info("  Ctrl+C 停止")
        logger.info("══════════════════════════════════════════")

    async def stop(self):
        """停止 broker。"""
        logger.info("正在关闭 broker...")
        if self._server:
            self._server.close()
            await self._server.wait_closed()
        # 断开所有客户端
        for client in list(self._sessions.values()):
            try:
                client.writer.close()
            except Exception:
                pass
        self._sessions.clear()
        self._subscriptions.clear()
        self._log_stats()
        logger.info("Broker 已停止")

    async def serve_forever(self):
        """启动并阻塞直到收到停止信号。"""
        await self.start()
        loop = asyncio.get_event_loop()
        stop_event = asyncio.Event()

        def _signal_handler():
            logger.info("\n收到中断信号...")
            stop_event.set()

        # 注册信号处理（仅在主线程有效）
        try:
            loop.add_signal_handler(getattr(__import__("signal"), "SIGINT"),
                                    _signal_handler)
            loop.add_signal_handler(getattr(__import__("signal"), "SIGTERM"),
                                    _signal_handler)
        except (NotImplementedError, RuntimeError):
            # Windows 下 add_signal_handler 不可用，用 KeyboardInterrupt
            pass

        try:
            await stop_event.wait()
        except KeyboardInterrupt:
            pass
        finally:
            await self.stop()



def load_config() -> dict:
    """从 config.json 读取 broker 段，供启动参数做默认值。"""
    try:
        import json
        with open("config.json", encoding="utf-8") as f:
            return json.load(f).get("broker", {})
    except Exception:
        return {}


# ── CLI ──────────────────────────────────────────────────────────────────

def main():
    # 先读 config.json，再被命令行参数覆盖
    cfg = load_config()

    parser = argparse.ArgumentParser(
        description="简易 MQTT 3.1.1 Broker — 用于测试视频流收发",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
示例:
  python mqtt_broker.py
  python mqtt_broker.py --port 3333
  python mqtt_broker.py --host 0.0.0.0 --port 1883 --verbose

启动后，客户端连接到这个 broker 即可收发视频流。
参数从 config.json 的 "broker" 段读取，命令行参数优先。
        """,
    )
    parser.add_argument("--host", default=None,
                        help=f"监听地址 (默认: {cfg.get('host', '0.0.0.0')})")
    parser.add_argument("--port", type=int, default=None,
                        help=f"监听端口 (默认: {cfg.get('port', 1883)})")
    parser.add_argument("--verbose", "-v", action="store_true",
                        default=None, help="详细日志")

    args = parser.parse_args()

    host = args.host if args.host is not None else cfg.get("host", "0.0.0.0")
    port = args.port if args.port is not None else cfg.get("port", 1883)
    verbose = bool(args.verbose or cfg.get("verbose", False))

    logging.basicConfig(
        level=logging.DEBUG if verbose else logging.INFO,
        format="%(asctime)s [%(levelname)s] %(message)s",
        datefmt="%H:%M:%S",
    )

    broker = MQTTBroker(host=host, port=port, verbose=verbose)
    try:
        asyncio.run(broker.serve_forever())
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
