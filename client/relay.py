# 시험용 TCP 중계: 127.0.0.1:<port> → <host>:<hport>  ('집에 돌아옴' 흉내)
import socket, sys, threading

port, host, hport = int(sys.argv[1]), sys.argv[2], int(sys.argv[3])

def pipe(a, b):
    try:
        while True:
            d = a.recv(65536)
            if not d:
                break
            b.sendall(d)
    except OSError:
        pass
    finally:
        for s in (a, b):
            try:
                s.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass

srv = socket.socket()
srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
srv.bind(("127.0.0.1", port))
srv.listen(64)
while True:
    c, _ = srv.accept()
    try:
        u = socket.create_connection((host, hport))
    except OSError:
        c.close()
        continue
    threading.Thread(target=pipe, args=(c, u), daemon=True).start()
    threading.Thread(target=pipe, args=(u, c), daemon=True).start()
