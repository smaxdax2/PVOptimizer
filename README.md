# PV Optimizer

Eine Python Anwendung zur Eigenverbrauchsoptimierung für Fronius Symo GEN24 10 Plus Wechselrichter.
Steuert ein lokales Relais (z.B. für Warmwasser) über den Raspberry Pi GPIO sowie TP-Link Kasa WLAN-Steckdosen via Netzwerk.

## Voraussetzungen

- Raspberry Pi mit installiertem OS (z.B. Raspberry Pi OS)
- Python 3.10 oder höher
- (Optional, nur für Warmwasser) Ein an den GPIO Pins angeschlossenes Relais

## Installation auf dem Raspberry Pi

1. Repository klonen oder Daten kopieren in einen Ordner (z.B. `/home/pi/PVOptimizer`).
2. Öffnen Sie ein Terminal und navigieren Sie in den Ordner:
   ```bash
   cd /home/pi/PVOptimizer
   ```
3. Erstellen Sie eine virtuelle Python-Umgebung (empfohlen) und aktivieren Sie diese:
   ```bash
   python -m venv venv
   source venv/bin/activate
   ```
4. Installieren Sie die Abhängigkeiten:
   ```bash
   pip install -r requirements.txt
   ```

## Starten der Anwendung

### Im Produktionsmodus (Raspberry Pi)
Führt echte GPIO-Schaltungen aus und liest vom Fronius Wechselrichter:

```bash
python main.py --host 0.0.0.0 --port 8000
```
Das Dashboard ist dann unter `http://<IP-des-Raspberry>:8000` erreichbar.

### Im Testmodus (PC / Simulation)
Liest keine echten GPIOs (mocked) und bietet Slider zur Simulation der Fronius-Werte:

```bash
python main.py --test --host 127.0.0.1 --port 8000
```
Öffnen Sie danach `http://localhost:8000/test.html` im Browser.

---

## Autostart einrichten (Systemd)

Damit das Programm nach einem Neustart des Raspberry Pi automatisch hochfährt, richten wir einen Systemd-Service ein.

1. Erstellen Sie eine Service-Datei:
   ```bash
   sudo nano /etc/systemd/system/pvoptimizer.service
   ```
2. Fügen Sie folgenden Inhalt ein (passen Sie die Pfade `/home/pi/...` ggf. an Ihren Benutzer an):

   ```ini
   [Unit]
   Description=PV Optimizer Service
   After=network.target

   [Service]
   User=pi
   WorkingDirectory=/home/pi/PVOptimizer
   Environment=PATH=/home/pi/PVOptimizer/venv/bin:$PATH
   ExecStart=/home/pi/PVOptimizer/venv/bin/python main.py --host 0.0.0.0 --port 80
   Restart=always
   RestartSec=10

   [Install]
   WantedBy=multi-user.target
   ```

   *(Achtung: Port 80 erfordert root-Rechte zum Binden, oder Sie setzen `CapabilityBoundingSet=CAP_NET_BIND_SERVICE` im Service, oder Sie belassen es bei Port 8000 und nutzen einen Nginx-Proxy. Wenn es bei 8000 bleiben soll, ersetzen Sie `--port 80` durch `--port 8000`)*.

3. Daemon neu laden und Service aktivieren:
   ```bash
   sudo systemctl daemon-reload
   sudo systemctl enable pvoptimizer.service
   sudo systemctl start pvoptimizer.service
   ```

4. Status überprüfen:
   ```bash
   sudo systemctl status pvoptimizer.service
   ```
