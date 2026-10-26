# WiFi X-Ray v2 鉴权帧协议

Android 端从 3.7.9.20 起只接受已鉴权的 `XRY2` / `RPT2` 帧，不再接受旧的 `XRY1` / `RPT1` 明文帧。

## 帧格式

所有整数都是大端序（network byte order）。

| 字段 | 长度 | 说明 |
|---|---:|---|
| magic | 4 | 图像为 ASCII `XRY2`，报告为 ASCII `RPT2` |
| timestamp | 8 | Unix 毫秒时间戳，与 RK3566 时钟偏差不得超过 5 分钟 |
| nonce | 16 | 每帧唯一的密码学随机数 |
| length | 4 | payload 字节数 |
| payload | length | JPEG/PNG 图像，或 UTF-8 报告 |
| mac | 32 | HMAC-SHA256 |

HMAC 输入是上表中从 `magic` 到 `payload` 的字节串联：

```text
HMAC-SHA256(shared_key, magic || timestamp || nonce || length || payload)
```

共享密钥位于 `app/build.gradle.kts` 的 `WIFI_XRAY_SHARED_KEY`，迷你主机必须配置相同值。

## Python 发送示例

```python
import hashlib
import hmac
import os
import socket
import struct
import time

HOST = "192.168.43.1"  # RK3566 在当前热点中的地址，按现场修改
PORT = 50001
KEY = os.environ["WGXRAY_SHARED_KEY"].encode("utf-8")

def frame(magic: bytes, payload: bytes, timestamp_ms: int | None = None) -> bytes:
    timestamp_ms = timestamp_ms or int(time.time() * 1000)
    nonce = os.urandom(16)
    header = magic + struct.pack(">q", timestamp_ms) + nonce + struct.pack(">i", len(payload))
    body = header + payload
    return body + hmac.new(KEY, body, hashlib.sha256).digest()

with socket.create_connection((HOST, PORT), timeout=5) as sock:
    with open("xray.jpg", "rb") as image_file:
        sock.sendall(frame(b"XRY2", image_file.read()))
```

注意：当前 Android 类名仍为 `WifiXrayServer`，但实现实际上是 RK3566 主动连接 `192.168.43.42:50001` 的 TCP 客户端。因此实际部署时，迷你主机应当监听 `0.0.0.0:50001`，并在已建立的连接上发送上述帧。
