/**
 * Sinden Screen Border Calibrator & Fullscreen Simulator
 * Controls border color, thickness, HSV ranges, and fullscreen overlay
 */

class BorderCalibrator {
  constructor() {
    this.currentColor = '#00E5FF';
    this.currentThickness = 14;
    
    // Preset HSV mappings for OpenCV inRange
    this.presets = {
      '#00E5FF': { name: 'CYAN', hMin: 85, hMax: 105, sMin: 140, sMax: 255, vMin: 120, vMax: 255, code: 0x01 },
      '#00FF44': { name: 'GREEN', hMin: 45, hMax: 75, sMin: 120, sMax: 255, vMin: 100, vMax: 255, code: 0x02 },
      '#FF0066': { name: 'MAGENTA', hMin: 140, hMax: 170, sMin: 130, sMax: 255, vMin: 120, vMax: 255, code: 0x03 },
      '#FFFFFF': { name: 'WHITE', hMin: 0, hMax: 180, sMin: 0, sMax: 40, vMin: 210, vMax: 255, code: 0x04 }
    };

    this.hMin = 85;
    this.hMax = 105;
    this.sMin = 140;
    this.sMax = 255;
    this.vMin = 120;
    this.vMax = 255;

    this.initElements();
    this.bindEvents();
    this.updatePreview();
  }

  initElements() {
    this.previewOverlay = document.getElementById('borderOverlayPreview');
    this.thicknessSlider = document.getElementById('borderThicknessSlider');
    this.thicknessValBadge = document.getElementById('borderThicknessVal');
    this.colorPicker = document.getElementById('customColorPicker');
    this.presetBtns = document.querySelectorAll('.btn-preset');
    
    this.hMinSlider = document.getElementById('hMinSlider');
    this.hMaxSlider = document.getElementById('hMaxSlider');
    this.sMinSlider = document.getElementById('sMinSlider');
    this.sMaxSlider = document.getElementById('sMaxSlider');
    this.vMinSlider = document.getElementById('vMinSlider');
    this.vMaxSlider = document.getElementById('vMaxSlider');

    this.packetBytesContainer = document.getElementById('packetHexBytes');
    this.fullscreenOverlay = document.getElementById('fullscreenBorderOverlay');
    this.fullscreenBorderLine = document.getElementById('fullscreenBorderLine');
    this.btnLaunchFullscreen = document.getElementById('btnLaunchFullscreen');
    this.btnExitFullscreen = document.getElementById('btnExitFullscreen');
  }

  bindEvents() {
    // Thickness
    if (this.thicknessSlider) {
      this.thicknessSlider.addEventListener('input', (e) => {
        this.currentThickness = parseInt(e.target.value, 10);
        if (this.thicknessValBadge) this.thicknessValBadge.textContent = `${this.currentThickness}px`;
        this.updatePreview();
      });
    }

    // Preset color buttons
    this.presetBtns.forEach(btn => {
      btn.addEventListener('click', () => {
        const color = btn.getAttribute('data-color');
        if (color) {
          this.setColor(color);
          this.presetBtns.forEach(b => b.classList.remove('active'));
          btn.classList.add('active');
          if (window.cyberAudio) window.cyberAudio.playBeep(980, 'sine', 0.04);
        }
      });
    });

    // Custom Color picker
    if (this.colorPicker) {
      this.colorPicker.addEventListener('input', (e) => {
        this.setColor(e.target.value.toUpperCase());
        this.presetBtns.forEach(b => b.classList.remove('active'));
      });
    }

    // HSV Slider events
    const hsvSliders = [
      { el: this.hMinSlider, key: 'hMin' },
      { el: this.hMaxSlider, key: 'hMax' },
      { el: this.sMinSlider, key: 'sMin' },
      { el: this.sMaxSlider, key: 'sMax' },
      { el: this.vMinSlider, key: 'vMin' },
      { el: this.vMaxSlider, key: 'vMax' }
    ];

    hsvSliders.forEach(({ el, key }) => {
      if (el) {
        el.addEventListener('input', (e) => {
          this[key] = parseInt(e.target.value, 10);
          const badge = document.getElementById(`${key}Val`);
          if (badge) badge.textContent = this[key];
          this.updatePacketPreview();
        });
      }
    });

    // Fullscreen Mode
    if (this.btnLaunchFullscreen) {
      this.btnLaunchFullscreen.addEventListener('click', () => this.enterFullscreen());
    }

    if (this.btnExitFullscreen) {
      this.btnExitFullscreen.addEventListener('click', () => this.exitFullscreen());
    }

    document.addEventListener('keydown', (e) => {
      if (e.key === 'Escape' && this.fullscreenOverlay && this.fullscreenOverlay.classList.contains('active')) {
        this.exitFullscreen();
      }
    });
  }

  setColor(hex) {
    this.currentColor = hex;
    if (this.colorPicker) this.colorPicker.value = hex;

    // Apply preset HSV if known
    if (this.presets[hex]) {
      const p = this.presets[hex];
      this.hMin = p.hMin; this.hMax = p.hMax;
      this.sMin = p.sMin; this.sMax = p.sMax;
      this.vMin = p.vMin; this.vMax = p.vMax;

      if (this.hMinSlider) this.hMinSlider.value = this.hMin;
      if (this.hMaxSlider) this.hMaxSlider.value = this.hMax;
      if (this.sMinSlider) this.sMinSlider.value = this.sMin;
      if (this.sMaxSlider) this.sMaxSlider.value = this.sMax;
      if (this.vMinSlider) this.vMinSlider.value = this.vMin;
      if (this.vMaxSlider) this.vMaxSlider.value = this.vMax;

      ['hMin', 'hMax', 'sMin', 'sMax', 'vMin', 'vMax'].forEach(k => {
        const b = document.getElementById(`${k}Val`);
        if (b) b.textContent = this[k];
      });
    }

    this.updatePreview();
  }

  updatePreview() {
    if (this.previewOverlay) {
      this.previewOverlay.style.boxShadow = `inset 0 0 0 ${this.currentThickness}px ${this.currentColor}`;
    }

    if (this.fullscreenBorderLine) {
      this.fullscreenBorderLine.style.boxShadow = `inset 0 0 0 ${this.currentThickness * 1.5}px ${this.currentColor}`;
    }

    this.updatePacketPreview();
  }

  // Calculate CRC8 Dallas/Maxim (Polynomial 0x31)
  computeCRC8(bytes) {
    let crc = 0x00;
    for (let b of bytes) {
      crc ^= b;
      for (let i = 0; i < 8; i++) {
        if ((crc & 0x80) !== 0) {
          crc = ((crc << 1) ^ 0x31) & 0xFF;
        } else {
          crc = (crc << 1) & 0xFF;
        }
      }
    }
    return crc;
  }

  updatePacketPreview() {
    if (!this.packetBytesContainer) return;

    // Packet structure:
    // [0xBB, CMD_SYNC_COLOR, H_MIN, H_MAX, S_MIN, S_MAX, V_MIN, V_MAX, THICKNESS, CRC8]
    const colorCode = this.presets[this.currentColor] ? this.presets[this.currentColor].code : 0x09;
    const rawBytes = [
      0xBB, // Sync Color Opcode
      colorCode,
      this.hMin & 0xFF,
      this.hMax & 0xFF,
      this.sMin & 0xFF,
      this.sMax & 0xFF,
      this.vMin & 0xFF,
      this.vMax & 0xFF,
      this.currentThickness & 0xFF
    ];

    const crc = this.computeCRC8(rawBytes);
    const fullPacket = [...rawBytes, crc];

    this.packetBytesContainer.innerHTML = fullPacket.map((b, i) => {
      let cls = 'hex-byte';
      if (i === 0) cls += ' header';
      if (i === fullPacket.length - 1) cls += ' crc';
      return `<span class="${cls}">0x${b.toString(16).toUpperCase().padStart(2, '0')}</span>`;
    }).join(' ');
  }

  enterFullscreen() {
    if (this.fullscreenOverlay) {
      this.fullscreenOverlay.classList.add('active');
      const elem = document.documentElement;
      if (elem.requestFullscreen) {
        elem.requestFullscreen().catch(() => {});
      }
      if (window.cyberAudio) window.cyberAudio.playBeep(1200, 'triangle', 0.08);
    }
  }

  exitFullscreen() {
    if (this.fullscreenOverlay) {
      this.fullscreenOverlay.classList.remove('active');
      if (document.fullscreenElement && document.exitFullscreen) {
        document.exitFullscreen().catch(() => {});
      }
      if (window.cyberAudio) window.cyberAudio.playBeep(600, 'triangle', 0.05);
    }
  }
}

window.addEventListener('DOMContentLoaded', () => {
  window.borderCalibrator = new BorderCalibrator();
});
