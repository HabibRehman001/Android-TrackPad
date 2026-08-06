"""Translates parsed packets into real mouse actions.

Deliberately dumb: all the "feel" tuning (sensitivity, smoothing,
acceleration) happens on the phone, where the user can control it live.
This file just executes whatever the phone already decided.
"""

from pynput.mouse import Controller, Button
from packet import (
    TYPE_MOVE,
    TYPE_CLICK,
    TYPE_DOUBLE_CLICK,
    TYPE_RIGHT_CLICK,
    TYPE_MIDDLE_CLICK,
    TYPE_SCROLL,
)

mouse = Controller()


def handle_packet(packet: dict):
    ptype = packet["type"]

    if ptype == TYPE_MOVE:
        mouse.move(packet["v1"], packet["v2"])
    elif ptype == TYPE_CLICK:
        mouse.click(Button.left, 1)
    elif ptype == TYPE_DOUBLE_CLICK:
        mouse.click(Button.left, 2)
    elif ptype == TYPE_RIGHT_CLICK:
        mouse.click(Button.right, 1)
    elif ptype == TYPE_MIDDLE_CLICK:
        mouse.click(Button.middle, 1)
    elif ptype == TYPE_SCROLL:
        mouse.scroll(0, packet["v1"])
    else:
        print(f"[mouse] Unknown packet type: {ptype}")
