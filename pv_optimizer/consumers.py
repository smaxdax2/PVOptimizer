import asyncio
import logging
from typing import Dict, List, Any
from kasa import Discover, SmartPlug as KasaSmartPlug

# Attempt to load RPi.GPIO, but fallback for PC Development
try:
    import RPi.GPIO as GPIO
    HAS_GPIO = True
except ImportError:
    HAS_GPIO = False

logger = logging.getLogger(__name__)

class GenericConsumer:
    def __init__(self, c_id: str, name: str, power_w: float, priority: int = 100):
        self.c_id = c_id
        self.name = name
        self.power_w = power_w
        self.priority = priority
        self.is_on = False
        self.is_manual_override = False
        self.is_online = True  # Always true for Water Heater, updated for Plugs

    async def turn_on(self):
        self.is_on = True

    async def turn_off(self):
        self.is_on = False

    def to_dict(self):
        return {
            "id": self.c_id,
            "name": self.name,
            "power_w": self.power_w,
            "priority": self.priority,
            "is_on": self.is_on,
            "is_manual_override": self.is_manual_override,
            "is_online": self.is_online,
            "type": self.__class__.__name__
        }


class WaterHeater(GenericConsumer):
    def __init__(self, c_id: str, name: str, power_w: float, gpio_pin: int, mock_gpio: bool = False):
        super().__init__(c_id, name, power_w, priority=0)
        self.gpio_pin = gpio_pin
        self.use_gpio = HAS_GPIO and not mock_gpio
        
        if self.use_gpio:
            GPIO.setmode(GPIO.BCM)
            GPIO.setup(self.gpio_pin, GPIO.OUT)
            GPIO.output(self.gpio_pin, GPIO.LOW)  # Starts OFF
            logger.info(f"Initialized Water Heater on GPIO {self.gpio_pin}")
        else:
            logger.warning(f"Mocking Water Heater GPIO {self.gpio_pin} (RPi.GPIO not available or mocked via flag)")

    async def turn_on(self):
        if not self.is_on:
            self.is_on = True
            logger.info(f"TURNING ON Water Heater: {self.name} ({self.power_w}W)")
            if self.use_gpio:
                GPIO.output(self.gpio_pin, GPIO.HIGH)
            else:
                logger.debug(f"[MOCK] GPIO {self.gpio_pin} HIGH")

    async def turn_off(self):
        if self.is_on:
            self.is_on = False
            logger.info(f"TURNING OFF Water Heater: {self.name}")
            if self.use_gpio:
                GPIO.output(self.gpio_pin, GPIO.LOW)
            else:
                logger.debug(f"[MOCK] GPIO {self.gpio_pin} LOW")

    def cleanup(self):
        if self.use_gpio:
            GPIO.cleanup()


class ConfiguredSmartPlug(GenericConsumer):
    def __init__(self, c_id: str, name: str, ip: str, power_w: float, is_enabled: bool = False, priority: int = 100):
        super().__init__(c_id, name, power_w, priority=priority)
        self.ip = ip
        self.is_enabled = is_enabled
        self.device: KasaSmartPlug = None
        self.is_online = False

    async def update_device(self, device: KasaSmartPlug):
        self.device = device
        self.is_online = True
        try:
            await self.device.update()
            self.is_on = self.device.is_on
            logger.info(f"Plug {self.name} at {self.ip} is online. Currently ON: {self.is_on}")
        except Exception as e:
            logger.error(f"Failed to update plug {self.ip}: {e}")
            self.is_online = False

    def mark_offline(self):
        self.is_online = False
        self.device = None
        logger.warning(f"Plug {self.name} at {self.ip} marked offline.")

    async def turn_on(self):
        if self.device and self.is_online and not self.is_on:
            try:
                await self.device.turn_on()
                self.is_on = True
                logger.info(f"TURNING ON Plug: {self.name} ({self.power_w}W)")
            except Exception as e:
                logger.error(f"Failed to turn on plug {self.name}: {e}")
                self.is_online = False
        elif not self.is_online:
            logger.warning(f"Cannot turn on {self.name}, device is offline.")

    async def turn_off(self):
        if self.device and self.is_online and self.is_on:
            try:
                await self.device.turn_off()
                self.is_on = False
                logger.info(f"TURNING OFF Plug: {self.name}")
            except Exception as e:
                logger.error(f"Failed to turn off plug {self.name}: {e}")
                self.is_online = False
        elif not self.is_online:
            logger.warning(f"Cannot turn off {self.name}, device is offline.")

    def to_dict(self):
        d = super().to_dict()
        d["ip"] = self.ip
        d["is_enabled"] = self.is_enabled
        return d


class ConsumerManager:
    def __init__(self):
        self.water_heater: WaterHeater = None
        self.plugs: Dict[str, ConfiguredSmartPlug] = {}
        self.on_config_change = None
        
    def load_from_config(self, config: dict, mock_gpio: bool = False):
        # Setup Water Heater
        wh_cfg = config.get("water_heater", {})
        if wh_cfg:
            self.water_heater = WaterHeater(
                c_id="hw_1",
                name=wh_cfg.get("name", "Warmwasser"),
                power_w=wh_cfg.get("power_w", 3500),
                gpio_pin=wh_cfg.get("gpio_pin", 17),
                mock_gpio=mock_gpio
            )

        # Retain state if plugs already exist
        plugs_cfg = config.get("plugs", [])
        new_plugs = {}
        for idx, p in enumerate(plugs_cfg):
            ip = p["ip"]
            c_id = f"plug_{ip.replace('.', '_')}"
            priority = p.get("priority", idx + 1)
            if c_id in self.plugs:
                # Update attributes but keep runtime state
                plug = self.plugs[c_id]
                plug.name = p["name"]
                plug.power_w = p["power_w"]
                plug.is_enabled = p.get("is_enabled", False)
                plug.priority = priority
                new_plugs[c_id] = plug
            else:
                new_plugs[c_id] = ConfiguredSmartPlug(
                    c_id=c_id,
                    name=p["name"],
                    ip=ip,
                    power_w=p.get("power_w", 1000),
                    is_enabled=p.get("is_enabled", False),
                    priority=priority
                )
        self.plugs = new_plugs

    def get_all_consumers(self) -> List[GenericConsumer]:
        consumers = []
        if self.water_heater:
            consumers.append(self.water_heater)
        sorted_plugs = sorted(self.plugs.values(), key=lambda p: (p.priority, p.name))
        consumers.extend(sorted_plugs)
        return consumers

    def _trigger_config_save(self):
        if self.on_config_change:
            # Rebuild plugs config list sorted by priority
            sorted_plugs = sorted(self.plugs.values(), key=lambda p: (p.priority, p.name))
            plugs_list = [
                {
                    "name": p.name,
                    "ip": p.ip,
                    "power_w": p.power_w,
                    "is_enabled": p.is_enabled,
                    "priority": p.priority
                }
                for p in sorted_plugs
            ]
            self.on_config_change(plugs_list)

    async def scan_plugs(self):
        """Scans network for plugs and matches with configured ones."""
        logger.info("Scanning network for TP-Link Kasa plugs...")
        config_changed = False
        try:
            found_devices = await Discover.discover()
            
            # Reset offline status for plugs not found
            found_ips = set(found_devices.keys())
            
            # 1. Update existing and mark offline
            for c_id, plug in self.plugs.items():
                if plug.ip in found_ips:
                    await plug.update_device(found_devices[plug.ip])
                else:
                    plug.mark_offline()
                    # Also user wants disabled if not found: "Wird ein Eintrag beim Scannen nicht mehr gefunden soll der nicht gefundene Eintrag disabled werden."
                    if plug.is_enabled:
                        plug.is_enabled = False
                        config_changed = True
                        
            # 2. Add new plugs found during scan
            existing_ips = {p.ip for p in self.plugs.values()}
            for ip, device in found_devices.items():
                if ip not in existing_ips:
                    logger.info(f"New plug discovered at {ip}, adding to configuration (disabled by default).")
                    c_id = f"plug_{ip.replace('.', '_')}"
                    alias = device.alias if device.alias else f"New Plug {ip}"
                    existing_prios = [p.priority for p in self.plugs.values()]
                    next_prio = (max(existing_prios) + 1) if existing_prios else 1
                    new_plug = ConfiguredSmartPlug(c_id=c_id, name=alias, ip=ip, power_w=1000, is_enabled=False, priority=next_prio)
                    await new_plug.update_device(device)
                    self.plugs[c_id] = new_plug
                    config_changed = True

            if config_changed:
                self._trigger_config_save()

        except Exception as e:
            logger.error(f"Error scanning for plugs: {e}")

    def cleanup(self):
        if self.water_heater:
            self.water_heater.cleanup()
