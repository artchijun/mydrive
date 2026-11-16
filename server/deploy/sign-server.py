# 서버 실행 파일 서명: python3 sign.py <파일>  → <파일>.sig (base64)
import sys, base64, os
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
k = Ed25519PrivateKey.from_private_bytes(base64.b64decode(open(os.path.join(os.path.dirname(os.path.abspath(__file__)), 'server-update.key')).read()))
data = open(sys.argv[1], 'rb').read()
open(sys.argv[1] + '.sig', 'w').write(base64.b64encode(k.sign(data)).decode() + '\n')
print(sys.argv[1] + '.sig')
