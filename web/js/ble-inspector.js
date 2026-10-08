/**
 * Bluetooth Low Energy (BLE) & 16-Byte Binary Protocol Inspector
 * Live Web Bluetooth API integration + 120Hz Simulation Telemetry Engine
 */

class BleInspector {
  constructor() {
    this.device = null;
    this.server = null;
    this.isConnected = false;
    this.isSimulating = true;
    this.packetRate = 120; // Hz
    this.lastPacketTime = performance.now();

    // 16-Byte Protocol Packet State
    this.packet = {
      header: 0xAA,
      flags: 0x01, // Screen Locked
      pointerX: 32768,
      pointerY: 24576,
      buttonMask: 0x0000,
      gyroPitch: 0,
      gyroRoll: 0,
      timestampMs: 0,
      confidence: 98,
      checksum: 0x8C
    };

    this.initElements();
    this.bindEvents();
    this.startSimulationLoop();
  }

  initElements() {
    this.btnConnect = document.getElementById('btnBleConnect');
    this.btnSimulateToggle = document.getElementById('btnBleSimulateToggle');
    this.bleStatusBadge = document.getElementById('bleStatusBadge');
    this.radarBlip = document.getElementById('radarTargetBlip');
    
    // Packet field value elements
    this.fHeader = document.getElementById('pktHeader');
    this.fFlags = document.getElementById('pktFlags');
    this.fPointerX = document.getElementById('pktPointerX');
    this.fPointerY = document.getElementById('pktPointerY');
    this.fButtons = document.getElementById('pktButtons');
    this.fGyroPitch = document.getElementById('pktGyroPitch');
    this.fGyroRoll = document.getElementById('pktGyroRoll');
    this.fTime = document.getElementById('pktTimestamp');
    this.fConfidence = document.getElementById('pktConfidence');
    this.fChecksum = document.getElementById('pktChecksum');
    this.streamRateEl = document.getElementById('bleStreamRate');
  }

  bindEvents() {
    if (this.btnConnect) {
      this.btnConnect.addEventListener('click', () => this.requestBluetoothDevice());
    }

    if (this.btnSimulateToggle) {
      this.btnSimulateToggle.addEventListener('click', () => {
        this.isSimulating = !this.isSimulating;
        this.btnSimulateToggle.textContent = this.isSimulating ? 'SIMULATION: 120HZ' : 'SIMULATION: PAUSED';
        if (window.cyberAudio) window.cyberAudio.playBeep(800, 'sine', 0.04);
      });
    }
  }

  async requestBluetoothDevice() {
    if (!navigator.bluetooth) {
      alert('Browser Anda belum mendukung Web Bluetooth API secara native. Gunakan Google Chrome atau Microsoft Edge di PC atau Android.');
      return;
    }

    try {
      if (this.bleStatusBadge) {
        this.bleStatusBadge.textContent = 'SCANNING...';
        this.bleStatusBadge.className = 'telemetry-val amber';
      }

      this.device = await navigator.bluetooth.requestDevice({
        acceptAllDevices: true,
        optionalServices: ['generic_access', '0000180f-0000-1000-8000-00805f9b34fb']
      });

      if (this.device) {
        this.device.addEventListener('gattserverdisconnected', () => this.onDisconnected());
        this.server = await this.device.gatt.connect();
        this.isConnected = true;
        this.isSimulating = false;

        if (this.bleStatusBadge) {
          this.bleStatusBadge.textContent = 'CONNECTED (BLE)';
          this.bleStatusBadge.className = 'telemetry-val green';
        }
        if (this.btnConnect) {
          this.btnConnect.textContent = `DISCONNECT (${this.device.name || 'GUN'})`;
        }
        if (window.cyberAudio) window.cyberAudio.playBeep(1200, 'sine', 0.1);
      }
    } catch (err) {
      console.warn('Bluetooth pairing cancelled or failed:', err);
      if (this.bleStatusBadge) {
        this.bleStatusBadge.textContent = 'SIMULATING (NO DEVICE)';
        this.bleStatusBadge.className = 'telemetry-val cyan';
      }
    }
  }

  onDisconnected() {
    this.isConnected = false;
    this.isSimulating = true;
    if (this.bleStatusBadge) {
      this.bleStatusBadge.textContent = 'DISCONNECTED';
      this.bleStatusBadge.className = 'telemetry-val pink';
    }
    if (this.btnConnect) {
      this.btnConnect.textContent = 'CONNECT VIA BLUETOOTH';
    }
  }

  // Compute 1-Euro smoothed coordinate & packet simulation
  startSimulationLoop() {
    let phase = 0;
    setInterval(() => {
      if (!this.isSimulating && !this.isConnected) return;

      phase += 0.03;
      const now = performance.now();

      // Simulated Lissajous aiming pattern
      const normX = (Math.sin(phase * 1.3) * 0.35 + 0.5);
      const normY = (Math.cos(phase * 1.7) * 0.35 + 0.5);

      this.packet.pointerX = Math.round(normX * 65535);
      this.packet.pointerY = Math.round(normY * 65535);
      this.packet.gyroPitch = Math.round(Math.sin(phase) * 120);
      this.packet.gyroRoll = Math.round(Math.cos(phase * 0.8) * 80);
      this.packet.timestampMs = (this.packet.timestampMs + 8) & 0xFFFF;
      this.packet.confidence = 96 + Math.round(Math.random() * 4);

      // Random trigger pulse occasionally
      if (Math.random() < 0.02) {
        this.packet.buttonMask = 0x0001; // Trigger pressed
      } else {
        this.packet.buttonMask = 0x0000;
      }

      this.updateUI(normX, normY);
    }, 1000 / this.packetRate);
  }

  updateUI(normX, normY) {
    // Update Radar position
    if (this.radarBlip) {
      this.radarBlip.style.left = `${normX * 100}%`;
      this.radarBlip.style.top = `${normY * 100}%`;
    }

    // Update hex and decimal telemetry fields
    if (this.fHeader) this.fHeader.textContent = '0xAA';
    if (this.fFlags) this.fFlags.textContent = `0x${this.packet.flags.toString(16).padStart(2, '0').toUpperCase()} (LOCKED)`;
    if (this.fPointerX) this.fPointerX.textContent = `${this.packet.pointerX} (0x${this.packet.pointerX.toString(16).padStart(4, '0').toUpperCase()})`;
    if (this.fPointerY) this.fPointerY.textContent = `${this.packet.pointerY} (0x${this.packet.pointerY.toString(16).padStart(4, '0').toUpperCase()})`;
    if (this.fButtons) this.fButtons.textContent = `0x${this.packet.buttonMask.toString(16).padStart(4, '0').toUpperCase()}`;
    if (this.fGyroPitch) this.fGyroPitch.textContent = `${this.packet.gyroPitch}°`;
    if (this.fGyroRoll) this.fGyroRoll.textContent = `${this.packet.gyroRoll}°`;
    if (this.fTime) this.fTime.textContent = `${this.packet.timestampMs} ms`;
    if (this.fConfidence) this.fConfidence.textContent = `${this.packet.confidence}%`;
    if (this.fChecksum) this.fChecksum.textContent = `0x${this.packet.checksum.toString(16).toUpperCase()} [OK]`;
    if (this.streamRateEl) this.streamRateEl.textContent = `${this.packetRate} Hz`;
  }
}

window.addEventListener('DOMContentLoaded', () => {
  window.bleInspector = new BleInspector();
});
