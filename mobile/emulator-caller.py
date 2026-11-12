import base64,json,os,socket,ssl,struct,time,sys
EXT=os.environ.get("AFIF_TEST_EXT","109")
SEC=os.environ.get("AFIF_TEST_CALLER_SECRET","")
if not SEC: raise SystemExit("no caller secret")
def frame(op,data):
    b=bytes(data); h=bytearray([0x80|op]); n=len(b)
    if n<126: h.append(0x80|n)
    elif n<65536: h.append(0x80|126); h+=struct.pack("!H",n)
    else: h.append(0x80|127); h+=struct.pack("!Q",n)
    m=os.urandom(4); h+=m; h+=bytes(x^m[i%4] for i,x in enumerate(b)); return bytes(h)
def rx(s,n):
    o=b""
    while len(o)<n:
        d=s.recv(n-len(o))
        if not d: raise EOFError
        o+=d
    return o
def rf(s):
    a,b=rx(s,2); op=a&15; n=b&127
    if n==126: n=struct.unpack("!H",rx(s,2))[0]
    elif n==127: n=struct.unpack("!Q",rx(s,8))[0]
    m=rx(s,4) if b&128 else None; p=rx(s,n)
    if m: p=bytes(x^m[i%4] for i,x in enumerate(p))
    return op,p
def connect():
    ctx=ssl.create_default_context(); s=ctx.wrap_socket(socket.create_connection(("pbx.powerchain.uk",443),timeout=10),server_hostname="pbx.powerchain.uk")
    key=base64.b64encode(os.urandom(16)).decode()
    s.sendall((f"GET /register/110 HTTP/1.1\r\nHost: pbx.powerchain.uk\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\nSec-WebSocket-Protocol: {SEC}\r\n\r\n").encode())
    h=b""
    while b"\r\n\r\n" not in h: h+=s.recv(4096)
    s.settimeout(3); return s
s=connect(); print("registered 110",flush=True)
s.sendall(frame(1,json.dumps({"type":"dial","number":EXT}).encode())); print("dial",EXT,flush=True)
end=time.time()+35; answered=False
while time.time()<end:
    try: op,p=rf(s)
    except socket.timeout: continue
    if op==1:
        t=p.decode(errors="replace"); print("text",t,flush=True)
        if '"ring"' in t: print("RING",flush=True)
        if '"answered"' in t:
            answered=True; break
    elif op==8: break
print("answered",answered,flush=True)
if answered:
    for i in range(100):
        s.sendall(frame(2,b"\x00"*320))
        try:
            op,p=rf(s)
            if op==2: print("audio",len(p),flush=True)
        except socket.timeout: pass
        time.sleep(0.02)
    time.sleep(5)
s.sendall(frame(1,json.dumps({"type":"hangup"}).encode()))
s.close(); print("done",flush=True)