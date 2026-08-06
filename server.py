"""TCP server that receives touch packets from the phone (via adb reverse)
and forwards them to the mouse controller."""

import socket
import threading

from config import HOST, PORT
from packet import decode_stream
import mouse


def handle_client(conn: socket.socket, addr):
    # Disable Nagle's algorithm on the accepted socket too. Nagle is a
    # per-socket, per-direction setting - the phone disabling it on its
    # end doesn't disable it here. Without this on both ends, the kernel
    # can still hold small packets waiting to coalesce them.
    conn.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)

    print(f"[server] Phone connected from {addr} (TCP_NODELAY on)")
    buffer = b""
    conn.settimeout(5)  # detect a dead connection instead of hanging forever
    try:
        while True:
            try:
                chunk = conn.recv(4096)
            except socket.timeout:
                continue
            if not chunk:
                break  # phone closed the connection
            buffer += chunk
            packets, buffer = decode_stream(buffer)
            for packet in packets:
                mouse.handle_packet(packet)
    except ConnectionResetError:
        pass
    finally:
        print(f"[server] Phone disconnected: {addr}")
        conn.close()


def main():
    server_socket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server_socket.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server_socket.bind((HOST, PORT))
    server_socket.listen(1)
    print(f"[server] Listening on {HOST}:{PORT}")
    print(f"[server] Run 'adb reverse tcp:{PORT} tcp:{PORT}' then open the app on your phone.")

    try:
        while True:
            conn, addr = server_socket.accept()
            # A thread per connection means an app restart / reconnect just works.
            threading.Thread(target=handle_client, args=(conn, addr), daemon=True).start()
    except KeyboardInterrupt:
        print("\n[server] Shutting down.")
    finally:
        server_socket.close()


if __name__ == "__main__":
    main()
