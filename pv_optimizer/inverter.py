import httpx
import logging
from typing import Dict, Any

logger = logging.getLogger(__name__)

class FroniusInverter:
    def __init__(self, ip_address: str):
        self.ip_address = ip_address
        self.base_url = f"http://{ip_address}/components/readable"
        self.client = httpx.AsyncClient(timeout=10.0)

    async def get_data(self) -> dict:
        """
        Fetches data from Fronius Symo GEN24.
        Returns a dict with:
        - pv_power_w: Current PV generation
        - battery_percent: Battery charge level
        - grid_power_w: Power from/to grid (+ is drawing from grid, - is injecting into grid)
        - load_power_w: Current house consumption
        """
        try:
            # We attempt to use the standard API for GEN24
            # Usually it's /solar_api/v1/GetPowerFlowRealtimeData.fcgi
            url = f"http://{self.ip_address}/solar_api/v1/GetPowerFlowRealtimeData.fcgi"
            response = await self.client.get(url)
            response.raise_for_status()
            data = response.json()
            
            site_data = data.get("Body", {}).get("Data", {}).get("Site", {})
            inverters = data.get("Body", {}).get("Data", {}).get("Inverters", {})
            
            # P_PV is usually null at night, or positive when generating
            pv_power = site_data.get("P_PV", 0)
            if pv_power is None:
                pv_power = 0
                
            # P_Grid is positive for import, negative for export
            grid_power = site_data.get("P_Grid", 0)
            if grid_power is None:
                grid_power = 0
                
            # P_Load is house consumption (usually negative, we make it positive)
            load_power = site_data.get("P_Load", 0)
            if load_power is not None:
                load_power = abs(load_power)
            else:
                load_power = 0
                
            # Battery level (SOC)
            soc = inverters.get("1", {}).get("SOC", 0.0) # Assuming inverter 1, fallback to 0
            
            return {
                "pv_power_w": float(pv_power),
                "grid_power_w": float(grid_power),
                "load_power_w": float(load_power),
                "battery_percent": float(soc)
            }
            
        except httpx.HTTPError as e:
            logger.error(f"Error fetching data from Fronius: {e}")
            return {
                "pv_power_w": 0.0,
                "grid_power_w": 0.0,
                "load_power_w": 0.0,
                "battery_percent": 0.0,
                "error": str(e)
            }

    async def close(self):
        await self.client.aclose()


class MockInverter:
    """Mock Inverter for testing on PC without Fronius hardware."""
    def __init__(self):
        self.pv_power_w = 0.0
        self.load_power_w = 500.0
        self.battery_percent = 50.0
        # Grid power is roughly load - pv +/- battery charge/discharge.
        # For simplicity in mock we let the user manually set it, or calculate:
        # P_Grid = P_Load - P_PV (simplification without battery charging power)
        self.grid_power_w = self.load_power_w - self.pv_power_w

    async def get_data(self) -> dict:
        self.grid_power_w = self.load_power_w - self.pv_power_w
        return {
            "pv_power_w": self.pv_power_w,
            "grid_power_w": self.grid_power_w,
            "load_power_w": self.load_power_w,
            "battery_percent": self.battery_percent
        }

    def set_mock_values(self, pv_power: float = None, load_power: float = None, battery_percent: float = None):
        if pv_power is not None:
            self.pv_power_w = pv_power
        if load_power is not None:
            self.load_power_w = load_power
        if battery_percent is not None:
            self.battery_percent = battery_percent
        
        self.grid_power_w = self.load_power_w - self.pv_power_w

    async def close(self):
        pass
