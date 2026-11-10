import asyncio
import logging
from typing import Dict, Any
from pv_optimizer.consumers import ConsumerManager, GenericConsumer
from pv_optimizer.inverter import FroniusInverter, MockInverter

logger = logging.getLogger(__name__)

class OptimizerLogic:
    def __init__(self, config: dict, inverter: FroniusInverter | MockInverter, consumer_manager: ConsumerManager):
        self.config = config
        self.inverter = inverter
        self.consumer_manager = consumer_manager
        
    async def run_iteration(self):
        logger.info("-" * 40)
        logger.info("Starting Optimizer Iteration")
        
        # 1. Fetch values
        data = await self.inverter.get_data()
        if "error" in data:
            logger.error(f"Inverter read error, skipping iteration: {data['error']}")
            return

        pv_power = data["pv_power_w"]
        grid = data["grid_power_w"]
        load = data["load_power_w"]
        battery = data["battery_percent"]
        
        logger.info(f"READINGS: PV: {pv_power:.0f}W | Load: {load:.0f}W | Grid: {grid:.0f}W | Battery: {battery:.1f}%")
        
        # Load thresholds
        bat_threshold = self.config.get("battery_threshold_percent", 90.0)
        
        # Grid < 0 means injecting into grid, > 0 means drawing from grid
        grid_injection = -grid if grid < 0 else 0
        grid_draw = grid if grid > 0 else 0

        # Separating out consumers that are available for automatic regulation
        # Skip manually overridden and offline ones. Plugs must be enabled.
        auto_consumers = [
            c for c in self.consumer_manager.get_all_consumers() 
            if not c.is_manual_override and c.is_online and getattr(c, 'is_enabled', True)
        ]
        
        wh = next((c for c in auto_consumers if c.c_id == "hw_1"), None)
        plugs = [c for c in auto_consumers if c.c_id != "hw_1"]
        # Sort plugs ascending by priority: 1 (highest priority) to N (lowest priority)
        plugs.sort(key=lambda p: (getattr(p, 'priority', 100), p.power_w))
        
        # Logic Flow
        # Case 1: Grid Draw > 0 -> We need to stop taking from grid.
        if grid_draw > 0:
            # Turn off items starting with LOWEST priority (highest priority number) first
            # Plugs first in reverse priority order, Water Heater last.
            active_plugs = sorted([p for p in plugs if p.is_on], key=lambda p: (getattr(p, 'priority', 100), p.power_w), reverse=True)
            for plug in active_plugs:
                if grid_draw <= 0:
                    break
                await plug.turn_off()
                grid_draw -= plug.power_w
                logger.info(f"-> Turned OFF {plug.name} (Prio {getattr(plug, 'priority', '?')}, {plug.power_w}W) to reduce grid draw. Est remaining draw: {max(0, grid_draw)}W")

            # Check Water Heater if still drawing
            if grid_draw > 0 and wh and wh.is_on:
                await wh.turn_off()
                grid_draw -= wh.power_w
                logger.info(f"-> Turned OFF Water Heater ({wh.power_w}W) to reduce grid draw. Est remaining draw: {max(0, grid_draw)}W")
                
        # Case 2: Grid Injection > 0 AND Battery is full enough -> we can optionally turn things on
        elif grid_injection > 0 and battery >= bat_threshold:
            # Priority 1: Water Heater
            if wh and not wh.is_on:
                if grid_injection >= wh.power_w:
                    await wh.turn_on()
                    grid_injection -= wh.power_w
                    logger.info(f"-> Turned ON Water Heater ({wh.power_w}W). Est remaining injection: {grid_injection}W")
            
            # Priority 2+: Plugs in priority order (1 -> N) via greedy waterfall
            inactive_plugs = [p for p in plugs if not p.is_on]
            for plug in inactive_plugs:
                if grid_injection >= plug.power_w:
                    await plug.turn_on()
                    grid_injection -= plug.power_w
                    logger.info(f"-> Turned ON {plug.name} (Prio {getattr(plug, 'priority', '?')}, {plug.power_w}W). Est remaining injection: {grid_injection}W")
                    
        else:
            if battery < bat_threshold and grid_injection > 0:
                logger.debug(f"Injection is {grid_injection}W, but battery is {battery}% < {bat_threshold}%. Doing nothing.")
            else:
                logger.debug("Grid is neutral or drawing, but no consumers to turn off. Doing nothing.")

        logger.info("Finished Optimizer Iteration")
