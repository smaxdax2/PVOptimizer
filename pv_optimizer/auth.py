import hashlib
import hmac
import ipaddress
import secrets
import time
from typing import List, Optional

def hash_password(password: str, salt: Optional[bytes] = None) -> str:
    """Hashes a password using PBKDF2-HMAC-SHA256 with 100,000 iterations."""
    if salt is None:
        salt = secrets.token_bytes(16)
    key = hashlib.pbkdf2_hmac("sha256", password.encode("utf-8"), salt, 100_000)
    return f"{salt.hex()}${key.hex()}"

def verify_password(password: str, stored_hash: str) -> bool:
    """Verifies a password against the stored salt$hash string."""
    try:
        salt_hex, key_hex = stored_hash.split("$")
        salt = bytes.fromhex(salt_hex)
        key = hashlib.pbkdf2_hmac("sha256", password.encode("utf-8"), salt, 100_000)
        return hmac.compare_digest(key.hex(), key_hex)
    except Exception:
        return False

def create_session_token(secret: str) -> str:
    """Creates a cryptographically signed session token: nonce:timestamp:signature."""
    ts = str(int(time.time()))
    nonce = secrets.token_hex(16)
    payload = f"{nonce}:{ts}"
    sig = hmac.new(secret.encode("utf-8"), payload.encode("utf-8"), hashlib.sha256).hexdigest()
    return f"{payload}:{sig}"

def verify_session_token(token_str: str, secret: str, max_age_days: int = 30) -> bool:
    """Verifies HMAC signature and expiration of a session token."""
    try:
        parts = token_str.split(":")
        if len(parts) != 3:
            return False
        nonce, ts_str, sig = parts
        ts = int(ts_str)
        # Check expiration (default 30 days)
        if time.time() - ts > max_age_days * 86400:
            return False
        payload = f"{nonce}:{ts_str}"
        expected_sig = hmac.new(secret.encode("utf-8"), payload.encode("utf-8"), hashlib.sha256).hexdigest()
        return hmac.compare_digest(sig, expected_sig)
    except Exception:
        return False

def get_client_ip(headers: dict, client_host: Optional[str]) -> str:
    """
    Extracts the real client IP address.
    Checks X-Forwarded-For (from Caddy / Nginx reverse proxy) first,
    then X-Real-IP, then falls back to direct socket client host.
    """
    # X-Forwarded-For can be a comma-separated list; first entry is the original client
    forwarded = headers.get("x-forwarded-for")
    if forwarded:
        client_ip = forwarded.split(",")[0].strip()
        if client_ip:
            return client_ip

    real_ip = headers.get("x-real-ip")
    if real_ip:
        return real_ip.strip()

    if client_host:
        return client_host

    return "127.0.0.1"

def is_local_or_trusted_ip(ip_str: str, trusted_subnets: Optional[List[str]] = None) -> bool:
    """
    Checks if an IP address belongs to the local home network or trusted subnets.
    RFC 1918 private ranges (192.168.x.x, 10.x.x.x, 172.16.x.x) and loopback are local.
    """
    try:
        # Strip IPv6 port if present or handle IPv4-mapped IPv6
        clean_ip = ip_str.strip("[]")
        if clean_ip.startswith("::ffff:"):
            clean_ip = clean_ip[7:]

        ip_obj = ipaddress.ip_address(clean_ip)

        # Standard private ranges (e.g. 192.168.178.x, 10.x.x.x) or loopback
        if ip_obj.is_loopback or ip_obj.is_private:
            return True

        # Custom trusted subnets from config if configured
        if trusted_subnets:
            for subnet in trusted_subnets:
                if ip_obj in ipaddress.ip_network(subnet, strict=False):
                    return True

        return False
    except ValueError:
        return False
