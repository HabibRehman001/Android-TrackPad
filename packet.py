"""Binary packet framing.

Each packet is a fixed 5 bytes: 1 byte type + two signed 16-bit
little-endian values. Fixed size means framing is just "wait for 5
bytes, slice, repeat" - no delimiter, no text parsing, which matters
when packets can arrive at up to ~240/sec from the phone's touch
sampling rate.
"""

import struct

PACKET_SIZE = 5
_STRUCT = struct.Struct("<Bhh")  # 1 byte type, 2 signed shorts, little-endian

TYPE_MOVE = 0
TYPE_CLICK = 1
TYPE_DOUBLE_CLICK = 2
TYPE_RIGHT_CLICK = 3
TYPE_MIDDLE_CLICK = 4
TYPE_SCROLL = 5


def decode_stream(buffer: bytes):
    """Takes raw bytes accumulated from the socket and returns
    (list_of_packets, remaining_buffer)."""
    packets = []
    while len(buffer) >= PACKET_SIZE:
        chunk, buffer = buffer[:PACKET_SIZE], buffer[PACKET_SIZE:]
        ptype, v1, v2 = _STRUCT.unpack(chunk)
        packets.append({"type": ptype, "v1": v1, "v2": v2})
    return packets, buffer
