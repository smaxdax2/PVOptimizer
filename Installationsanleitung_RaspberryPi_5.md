# Installationsanleitung: PV Optimizer auf Raspberry Pi 5

Diese Anleitung beschreibt detailliert die Einrichtung des PV Optimizer Dashboards auf einem Raspberry Pi 5 mit dem neuesten **Raspberry Pi OS (Bookworm)**. 

*Hinweis: Ein PDF-Export dieser Datei lässt sich in den meisten Programmen ganz einfach über "Drucken -> Als PDF speichern" erzeugen (oder mit Markdown-Readern wie Typora/VS Code exportieren).*

---

## 1. Vorbereitungen am Raspberry Pi
Da das aktuelle Raspberry Pi OS "Bookworm" global installierte Python-Pakete (via `pip`) standardmäßig blockiert (PEP 668 Sicherheitsvorschrift), ist die Nutzung einer *virtuellen Umgebung* zwingend erforderlich. Außerdem besitzt der Raspberry Pi 5 einen neuen I/O-Chip (RP1), weshalb wir für die Relais-Steuerung `rpi-lgpio` anstelle des alten, nicht mehr ganz kompatiblen `RPi.GPIO` nutzen.

1. **System aktualisieren:**
   Öffne das Terminal am Raspberry Pi (oder verbinde dich via SSH) und führe aus:
   ```bash
   sudo apt update && sudo apt upgrade -y
   ```

2. **Abhängigkeiten für die Umgebung installieren:**
   ```bash
   sudo apt install -y python3-venv python3-pip git
   ```

---

## 2. Programm auf den Raspberry kopieren
Wechsle in ein passendes Verzeichnis, in dem du den PV Optimizer ablegen möchtest (z.B. in dein Benutzer-Home-Verzeichnis).

```bash
cd ~
```
Falls du den Code via Git lädst:
```bash
git clone <URL-zu-deinem-Repository> PVOptimizer
cd PVOptimizer
```
*(Alternativ: Kopiere den gesamten Ordner `PVOptimizer` einfach per USB-Stick oder SFTP-Client wie WinSCP auf den Raspberry Pi und navigiere im Terminal mit `cd ~/PVOptimizer` in den entstandenen Ordner.)*

---

## 3. Python-Umgebung und Abhängigkeiten installieren
Erstelle nun den gekapselten Bereich (virtuelle Umgebung), damit die Pakete sicher und systemunabhängig installiert werden können:

```bash
python3 -m venv venv
```

Aktiviere die Umgebung:
```bash
source venv/bin/activate
```
*(Die Befehlszeile in deinem Terminal sollte nun ein `(venv)` am Anfang anzeigen.)*

Installiere die benötigten Standard-Pakete. Da du einen **Raspberry Pi 5** hast, fügen wir im gleichen Schritt den passenden Treiber für das Relais hinzu (`rpi-lgpio` arbeitet exakt als Drop-in-Replacement für `RPi.GPIO`):

```bash
pip install -r requirements.txt
pip install rpi-lgpio
```

---

## 4. Webpage / Dashboard testen
Bevor wir den dauerhaften Autostart einrichten, testen wir, ob die Webpage lokal am Raspberry Pi aufrufbar ist:

```bash
python main.py --host 0.0.0.0 --port 8000
```

- Öffne nun an einem PC oder Handy in deinem Netzwerk den Browser.
- Gib die IP-Adresse des Raspberry Pis gefolgt von Port 8000 ein (z.B. `http://192.168.178.50:8000`).
- Wenn das Dashboard fehlerfrei erscheint und die Webpage funktioniert, kehre zum Raspberry Pi Terminal zurück.
- Drücke dort `Strg + C`, um den manuellen Test-Server wieder zu stoppen.

---

## 5. Autostart bei Neustart einrichten (Systemd)
Damit der PV Optimizer (die Webpage und die Hintergrundprozesse) automatisch hochfährt – auch nachdem der Pi z.B. mal den Strom verliert – legen wir einen sogenannten "Systemd-Service" an.

1. **Service-Datei erstellen:**
   ```bash
   sudo nano /etc/systemd/system/pvoptimizer.service
   ```

2. **Inhalt einfügen:**
   Kopiere den folgenden Text hinein. 
   **Wichtig:** Achte darauf, dass du den Nutzernamen `DEIN_BENUTZERNAME` durch den tatsächlichen Loginnamen deines Raspberry Pi-Kontos ersetzt (auf neuen Images ist das oft der von dir bei der Installation vergebene Name, früher war es standardmäßig `pi`).

   ```ini
   [Unit]
   Description=PV Optimizer Web Dashboard
   After=network-online.target
   Wants=network-online.target

   [Service]
   Type=simple
   User=DEIN_BENUTZERNAME
   WorkingDirectory=/home/DEIN_BENUTZERNAME/PVOptimizer
   Environment="PATH=/home/DEIN_BENUTZERNAME/PVOptimizer/venv/bin:$PATH"
   ExecStart=/home/DEIN_BENUTZERNAME/PVOptimizer/venv/bin/python main.py --host 0.0.0.0 --port 8000
   Restart=always
   RestartSec=10

   [Install]
   WantedBy=multi-user.target
   ```

   *(Speichere die Datei in `nano`, indem du `Strg + O`, dann `Enter` drückst. Danach schließt du den Editor mit `Strg + X`)*.

3. **Service aktivieren und starten:**
   Führe nun nacheinander diese drei Befehle aus, um den Dienst bei Linux zu registrieren:
   ```bash
   sudo systemctl daemon-reload
   sudo systemctl enable pvoptimizer.service
   sudo systemctl start pvoptimizer.service
   ```

4. **Erfolgskontrolle:**
   Mit folgendem Befehl kannst du abschließend prüfen, ob das Programm erfolgreich als Hintergrunddienst läuft. Es sollte grün "active (running)" anzeigen:
   ```bash
   sudo systemctl status pvoptimizer.service
   ```
   *(Beende die Statusanzeige, indem du die Taste `q` drückst).*

**Fertig!** Dein Raspberry Pi 5 lädt dein Dashboard und deine Automatisierungslogik nun standardmäßig im Hintergrund bei jedem Systemstart.
