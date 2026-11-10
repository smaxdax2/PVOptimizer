import asyncio
import json
import logging
import os
import argparse
import time
from typing import Dict, Any, List

from fastapi import FastAPI, BackgroundTasks, HTTPException
from fastapi.staticfiles import StaticFiles
from fastapi.responses import HTMLResponse
from pydantic import BaseModel
import uvicorn

from pv_optimizer.inverter import FroniusInverter, MockInverter
from pv_optimizer.consumers import ConsumerManager
from pv_optimizer.logic import OptimizerLogic

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
        save_config()

def save_config():
    with open(CONFIG_FILE, "w", encoding="utf-8") as f:
        json.dump(config, f, indent=4)

async def background_scanner():
    # Initial scan is performed in startup_event, so start counting from now
    last_run = time.time()
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

async def background_optimizer():
    last_run = time.time()
    while True:
        interval = config.get("optimize_interval_sec", 60)
        if time.time() - last_run >= interval:
            try:
                await optimizer.run_iteration()
            except Exception as e:
                logger.error(f"Error in optimizer loop: {e}")
            last_run = time.time()
        await asyncio.sleep(1)

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
    asyncio.create_task(background_scanner())
    asyncio.create_task(background_optimizer())

@app.on_event("shutdown")
async def shutdown_event():
    logger.info("Shutting down PV Optimizer...")
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
    resp["mock_inverter_enabled"] = mock_inverter_enabled
    resp["mock_gpio_enabled"] = mock_gpio_enabled
    return resp

@app.post("/api/config")
async def update_config(new_config: ConfigUpdate):
    global config
    new_dict = new_config.dict()
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
