import secrets
import base64

random_bytes = secrets.token_bytes(32)
secret = base64.b64encode(random_bytes).decode("utf-8")

print(secret)