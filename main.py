import asyncio
import json
import logging
import os
import argparse
import secrets
import time
from typing import Dict, Any, List, Optional

from fastapi import FastAPI, BackgroundTasks, HTTPException, Request, Response
from fastapi.staticfiles import StaticFiles
from fastapi.responses import HTMLResponse, JSONResponse, RedirectResponse
from pydantic import BaseModel
import uvicorn

from pv_optimizer.inverter import FroniusInverter, MockInverter
from pv_optimizer.consumers import ConsumerManager
from pv_optimizer.logic import OptimizerLogic
from pv_optimizer.auth import (
    hash_password, verify_password,
    create_session_token, verify_session_token,
    get_client_ip, is_local_or_trusted_ip
)

logging.basicConfig(level=logging.INFO, format="%(asctime)s - %(name)s - %(levelname)s - %(message)s")
logger = logging.getLogger(__name__)

# Constants
# Constants
CONFIG_FILE = "config.json"

# GLOBALS
app = FastAPI(title="PV Optimizer")
config = {}
inverter = None
consumer_manager = ConsumerManager()
optimizer = None
mock_inverter_enabled = False
mock_gpio_enabled = False

@app.middleware("http")
async def remote_auth_middleware(request: Request, call_next):
    path = request.url.path

    # 1. Exempt public auth routes and static assets
    if path in ("/login", "/logout", "/api/auth/status"):
        return await call_next(request)
    if path.endswith((".css", ".js", ".png", ".jpg", ".ico", ".svg")):
        return await call_next(request)

    auth_cfg = config.get("auth", {})
    if not auth_cfg.get("enabled", True):
        return await call_next(request)

    # 2. Check client IP
    client_ip = get_client_ip(dict(request.headers), request.client.host if request.client else None)
    is_local = is_local_or_trusted_ip(client_ip, auth_cfg.get("trusted_subnets"))

    if is_local:
        # Local home network access: grant immediate pass-through without login
        return await call_next(request)

    # 3. External / remote access: verify signed session cookie
    session_token = request.cookies.get("pv_session")
    secret = auth_cfg.get("session_secret", "")
    is_authenticated = bool(session_token and secret and verify_session_token(session_token, secret))

    if is_authenticated:
        return await call_next(request)

    # 4. Unauthorized remote access
    if path.startswith("/api/"):
        return JSONResponse(
            status_code=401,
            content={
                "detail": "Unauthorized - Fernzugriff erfordert Anmeldung",
                "is_local": False,
                "client_ip": client_ip
            }
        )

    return RedirectResponse(url=f"/login?next={path}", status_code=303)

# Models for API
class PlugConfig(BaseModel):
    name: str
    ip: str
    power_w: float
    is_enabled: bool = False
    priority: int = 100

class ConfigUpdate(BaseModel):
    fronius_ip: str
    battery_threshold_percent: float
    water_heater: dict
    plugs: List[PlugConfig]
    scan_interval_sec: int = 3600
    optimize_interval_sec: int = 60
    auth: Optional[dict] = None

class LoginRequest(BaseModel):
    password: str
    remember: bool = True

class PasswordChangeRequest(BaseModel):
    old_password: Optional[str] = None
    new_password: str

class MockUpdate(BaseModel):
    pv_power: float = None
    load_power: float = None
    battery_percent: float = None

class OverrideUpdate(BaseModel):
    is_manual_override: bool

class ToggleUpdate(BaseModel):
    is_on: bool

def load_config():
    global config
    save_needed = False
    if os.path.exists(CONFIG_FILE):
        with open(CONFIG_FILE, "r", encoding="utf-8") as f:
            config = json.load(f)
    else:
        logger.warning(f"Config file {CONFIG_FILE} not found. Using defaults.")
        config = {
            "fronius_ip": "192.168.1.100",
            "battery_threshold_percent": 90.0,
            "water_heater": {
                "gpio_pin": 17,
                "power_w": 3500,
                "name": "Warmwasser"
            },
            "plugs": [],
            "scan_interval_sec": 3600,
            "optimize_interval_sec": 60
        }
        save_needed = True

    # Ensure auth settings are present
    auth = config.setdefault("auth", {})
    if "enabled" not in auth:
        auth["enabled"] = True
        save_needed = True
    if "password_hash" not in auth:
        auth["password_hash"] = hash_password("admin")
        save_needed = True
    if "session_secret" not in auth:
        auth["session_secret"] = secrets.token_hex(32)
        save_needed = True
    if "trusted_subnets" not in auth:
        auth["trusted_subnets"] = ["192.168.0.0/16", "10.0.0.0/8", "172.16.0.0/12", "127.0.0.0/8"]
        save_needed = True

    if save_needed:
        save_config()

def save_config():
    with open(CONFIG_FILE, "w", encoding="utf-8") as f:
        json.dump(config, f, indent=4)

background_tasks: List[asyncio.Task] = []

async def background_scanner():
    # Initial scan is performed in startup_event, so start counting from now
    last_run = time.time()
    try:
        while True:
            interval = config.get("scan_interval_sec", 3600)
            if time.time() - last_run >= interval:
                try:
                    logger.info("Executing periodic plug scan...")
                    await consumer_manager.scan_plugs()
                    logger.info("Plug scan completed.")
                except Exception as e:
                    logger.error(f"Error in background scanner: {e}")
                last_run = time.time()
            await asyncio.sleep(1)
    except asyncio.CancelledError:
        logger.info("Background scanner stopped.")

async def background_optimizer():
    last_run = time.time()
    try:
        while True:
            interval = config.get("optimize_interval_sec", 60)
            if time.time() - last_run >= interval:
                try:
                    await optimizer.run_iteration()
                except Exception as e:
                    logger.error(f"Error in optimizer loop: {e}")
                last_run = time.time()
            await asyncio.sleep(1)
    except asyncio.CancelledError:
        logger.info("Background optimizer stopped.")

def auto_save_config_callback(plugs_list):
    global config
    config["plugs"] = plugs_list
    save_config()
    logger.info("Configuration auto-saved after plug scan.")

@app.on_event("startup")
async def startup_event():
    global inverter, optimizer
    logger.info(f"Starting PV Optimizer. Mock Inverter: {mock_inverter_enabled}, Mock GPIO: {mock_gpio_enabled}")
    load_config()
    
    if mock_inverter_enabled:
        inverter = MockInverter()
    else:
        inverter = FroniusInverter(config.get("fronius_ip", "192.168.1.100"))
        
    consumer_manager.on_config_change = auto_save_config_callback    
    consumer_manager.load_from_config(config, mock_gpio=mock_gpio_enabled)
    
    # Run initial scan immediately
    await consumer_manager.scan_plugs()
    
    optimizer = OptimizerLogic(config, inverter, consumer_manager)

    # Start loop tasks
    background_tasks.append(asyncio.create_task(background_scanner()))
    background_tasks.append(asyncio.create_task(background_optimizer()))

@app.on_event("shutdown")
async def shutdown_event():
    logger.info("Shutting down PV Optimizer...")
    for t in background_tasks:
        t.cancel()
    consumer_manager.cleanup()
    if inverter:
        await inverter.close()

# API Endpoints
@app.get("/api/status")
async def get_status():
    if not inverter:
        raise HTTPException(status_code=503, detail="Inverter not ready")
    data = await inverter.get_data()
    return data

@app.get("/api/consumers")
async def get_consumers():
    return [c.to_dict() for c in consumer_manager.get_all_consumers()]

@app.post("/api/consumers/{c_id}/override")
async def set_consumer_override(c_id: str, update: OverrideUpdate):
    for c in consumer_manager.get_all_consumers():
        if c.c_id == c_id:
            c.is_manual_override = update.is_manual_override
            logger.info(f"Consumer {c.name} manual override set to {c.is_manual_override}")
            return c.to_dict()
    raise HTTPException(status_code=404, detail="Consumer not found")

@app.post("/api/consumers/{c_id}/toggle")
async def toggle_consumer(c_id: str, update: ToggleUpdate):
    for c in consumer_manager.get_all_consumers():
        if c.c_id == c_id:
            if not c.is_manual_override:
                raise HTTPException(status_code=400, detail="Cannot toggle manually. Override is OFF.")
            if update.is_on:
                await c.turn_on()
            else:
                await c.turn_off()
            return c.to_dict()
    raise HTTPException(status_code=404, detail="Consumer not found")

@app.get("/api/config")
async def get_config():
    # Merge simulation flags into config response so frontend can read them
    resp = dict(config)
    # Sanitize sensitive auth fields
    if "auth" in resp:
        safe_auth = dict(resp["auth"])
        safe_auth.pop("password_hash", None)
        safe_auth.pop("session_secret", None)
        resp["auth"] = safe_auth
    resp["mock_inverter_enabled"] = mock_inverter_enabled
    resp["mock_gpio_enabled"] = mock_gpio_enabled
    return resp

@app.post("/api/config")
async def update_config(new_config: ConfigUpdate):
    global config
    new_dict = new_config.dict()
    # Preserve existing password_hash and session_secret if auth is updated
    if "auth" in new_dict and isinstance(new_dict["auth"], dict):
        existing_auth = config.get("auth", {})
        for k in ("password_hash", "session_secret"):
            if k not in new_dict["auth"] and k in existing_auth:
                new_dict["auth"][k] = existing_auth[k]
    elif "auth" in config and ("auth" not in new_dict or new_dict["auth"] is None):
        new_dict["auth"] = config["auth"]

    config.update(new_dict)
    save_config()
    # Reload config into components
    consumer_manager.load_from_config(config, mock_gpio=mock_gpio_enabled)
    optimizer.config = config
    if not mock_inverter_enabled:
        inverter.ip_address = config.get("fronius_ip")
        inverter.base_url = f"http://{inverter.ip_address}/components/readable"
    logger.info("Configuration updated.")
    return config

@app.post("/api/scan")
async def trigger_scan():
    await consumer_manager.scan_plugs()
    return {"status": "success"}

@app.post("/api/mock")
async def update_mock_values(update: MockUpdate):
    if not mock_inverter_enabled:
        raise HTTPException(status_code=400, detail="Inverter mock mode not enabled")
    inverter.set_mock_values(update.pv_power, update.load_power, update.battery_percent)
    return {"status": "success"}

# Authentication Endpoints
@app.get("/login", response_class=HTMLResponse)
async def login_page(request: Request):
    auth_cfg = config.get("auth", {})
    client_ip = get_client_ip(dict(request.headers), request.client.host if request.client else None)
    is_local = is_local_or_trusted_ip(client_ip, auth_cfg.get("trusted_subnets"))
    session_token = request.cookies.get("pv_session")
    secret = auth_cfg.get("session_secret", "")

    # If already local or authenticated, redirect to dashboard
    if is_local or (session_token and secret and verify_session_token(session_token, secret)):
        return RedirectResponse(url="/", status_code=303)

    login_path = os.path.join("static", "login.html")
    if os.path.exists(login_path):
        with open(login_path, "r", encoding="utf-8") as f:
            return HTMLResponse(content=f.read())
    return HTMLResponse(content="<h2>Login erforderlich</h2>")

@app.post("/login")
async def do_login(req: LoginRequest):
    auth_cfg = config.get("auth", {})
    stored_hash = auth_cfg.get("password_hash", "")
    if not stored_hash or not verify_password(req.password, stored_hash):
        raise HTTPException(status_code=401, detail="Falsches Passwort.")

    secret = auth_cfg.get("session_secret", "")
    token = create_session_token(secret)
    max_age = 30 * 86400 if req.remember else 86400

    res = JSONResponse({"status": "success", "message": "Angemeldet"})
    res.set_cookie(
        key="pv_session",
        value=token,
        max_age=max_age,
        httponly=True,
        samesite="lax",
        path="/"
    )
    return res

@app.post("/logout")
async def do_logout():
    res = JSONResponse({"status": "success", "message": "Abgemeldet"})
    res.delete_cookie(key="pv_session", path="/")
    return res

@app.get("/api/auth/status")
async def get_auth_status(request: Request):
    auth_cfg = config.get("auth", {})
    client_ip = get_client_ip(dict(request.headers), request.client.host if request.client else None)
    is_local = is_local_or_trusted_ip(client_ip, auth_cfg.get("trusted_subnets"))
    session_token = request.cookies.get("pv_session")
    secret = auth_cfg.get("session_secret", "")
    is_authenticated = bool(session_token and secret and verify_session_token(session_token, secret))
    return {
        "is_local": is_local,
        "is_authenticated": is_authenticated,
        "auth_enabled": auth_cfg.get("enabled", True),
        "client_ip": client_ip
    }

@app.post("/api/auth/password")
async def change_password(req: PasswordChangeRequest, request: Request):
    auth_cfg = config.get("auth", {})
    client_ip = get_client_ip(dict(request.headers), request.client.host if request.client else None)
    is_local = is_local_or_trusted_ip(client_ip, auth_cfg.get("trusted_subnets"))
    stored_hash = auth_cfg.get("password_hash", "")

    if not is_local and stored_hash:
        if not req.old_password or not verify_password(req.old_password, stored_hash):
            raise HTTPException(status_code=403, detail="Aktuelles Passwort ist nicht korrekt.")

    if len(req.new_password) < 4:
        raise HTTPException(status_code=400, detail="Das Passwort muss mindestens 4 Zeichen lang sein.")

    auth_cfg["password_hash"] = hash_password(req.new_password)
    config["auth"] = auth_cfg
    save_config()
    logger.info("Dashboard password updated.")
    return {"status": "success", "message": "Passwort erfolgreich aktualisiert."}

# Serve static files for frontend. We'll mount it at /
app.mount("/", StaticFiles(directory="static", html=True), name="static")

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="PV Optimizer")
    parser.add_argument("--test", action="store_true", help="Run in test/simulation mode (mocks inverter and GPIO)")
    parser.add_argument("--mock-inverter", action="store_true", help="Mock the Fronius inverter")
    parser.add_argument("--mock-gpio", action="store_true", help="Mock the GPIO relays")
    parser.add_argument("--host", default="0.0.0.0", help="Host IP to bind to")
    parser.add_argument("--port", type=int, default=8000, help="Port to bind to")
    args = parser.parse_args()
    
    if args.test:
        mock_inverter_enabled = True
        mock_gpio_enabled = True
    else:
        mock_inverter_enabled = args.mock_inverter
        mock_gpio_enabled = args.mock_gpio
    
    # Needs to create directory for static files before running
    if not os.path.exists("static"):
        os.makedirs("static")
    
    logger.info(f"Starting auto-optimizer. Mock Inverter: {mock_inverter_enabled}, Mock GPIO: {mock_gpio_enabled}")
    uvicorn.run(app, host=args.host, port=args.port, reload=False)
