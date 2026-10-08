/**
 * Emulator Guides & Interactive Button Mapping
 * Teknoparrot, Dolphin, PCSX2, MAME, Model 2
 */

class EmulatorGuideManager {
  constructor() {
    this.emulatorData = {
      teknoparrot: {
        title: 'Teknoparrot (Arcade Modern)',
        mode: 'Absolute Mouse Pointer / RawInput',
        desc: 'Ideal untuk arcade modern seperti House of the Dead Scarlet Dawn, Time Crisis 5, Transformers Human Alliance, Jurassic Park Arcade.',
        guide: [
          'Buka Teknoparrot UI -> Pilih Game -> Game Settings.',
          'Pastikan opsi "General - Input API" diatur ke "RawInput" atau "DirectInput".',
          'Buka Controller Setup -> Gun X arahkan ke Axis X (Cursor X), Gun Y ke Axis Y (Cursor Y).',
          'Trigger di-bind ke Left Click Mouse, Reload di-bind ke Right Click Mouse.'
        ],
        configCode: `# Teknoparrot UserProfile Configuration
[Input]
GunX = Cursor_X
GunY = Cursor_Y
Trigger = Mouse_LeftButton
Reload = Mouse_RightButton
OffscreenReload = Enabled
Sensitivity = 1.0`
      },
      dolphin: {
        title: 'Dolphin (Wii & GameCube)',
        mode: 'Emulated Wii Remote Pointer',
        desc: 'Cocok untuk House of the Dead 2 & 3 Return, Resident Evil The Darkside / Umbrella Chronicles, Dead Space Extraction.',
        guide: [
          'Buka Dolphin -> Controllers -> Emulated Wii Remote -> Configure.',
          'Pada tab "Motion Simulation" -> "Point", arahkan sumbu ke `Cursor X- / X+` dan `Cursor Y- / Y+`.',
          'Pada tab "General and Options" -> Assign Button B ke Trigger (Left Click / L2).',
          'Centang opsi "Total Yaw" dan "Total Pitch" untuk kalibrasi layar lebar 16:9.'
        ],
        configCode: `[Wiimote1]
Source = 1
Point/Up = \`Cursor Y-\`
Point/Down = \`Cursor Y+\`
Point/Left = \`Cursor X-\`
Point/Right = \`Cursor X+\`
Buttons/B = \`Click 0\`
Buttons/A = \`Click 1\``
      },
      pcsx2: {
        title: 'PCSX2 (PlayStation 2 GunCon 2)',
        mode: 'ViGEmBus Gamepad / USB Mouse',
        desc: 'Untuk judul legendaris Time Crisis 2 & 3, Vampire Night, Dino Stalker, Crisis Zone.',
        guide: [
          'Pastikan driver ViGEmBus telah terpasang di PC Server.',
          'Buka PCSX2 -> Settings -> Controllers -> USB Port 1 -> Pilih "GunCon 2".',
          'Pilih device input: "ViGEm Xbox 360 Controller" atau "Mouse Pointer".',
          'Assign Trigger ke L2 / Left Mouse Button, Reload / Pedal ke A / Cross Button.'
        ],
        configCode: `[USB1]
Type = GunCon2
Device = ViGEmBus/0
Trigger = Pad1/L2
Reload = Pad1/R2
Action = Pad1/Cross
Start = Pad1/Start`
      },
      mame: {
        title: 'MAME (Arcade Retro Clones)',
        mode: 'Lightgun Absolute Device',
        desc: 'Dukungan penuh arcade klasik: Area 51, Point Blank 1-3, Operation Wolf, Police Trainer.',
        guide: [
          'Edit file mame.ini atau buka menu In-Game Input (tekan Tab).',
          'Set parameter lightgun = 1 dan lightgun_device = mouse.',
          'Set offscreen_reload = 1 agar membidik di luar monitor otomatis mengisi peluru.',
          'Ubah Lightgun X Analog ke Gun X dan Lightgun Y Analog ke Gun Y.'
        ],
        configCode: `# mame.ini Core Input Options
lightgun                  1
lightgun_device           mouse
offscreen_reload          1
dual_lightgun             0
mouse                     1`
      },
      model2: {
        title: 'Model 2 / Model 3 Emulator',
        mode: 'RawInput Mouse',
        desc: 'Untuk Virtua Cop 1 & 2, Behind Enemy Lines, The Lost World Jurassic Park.',
        guide: [
          'Buka file EMULATOR.INI pada folder Sega Model 2 Emulator.',
          'Cari baris [Input] dan ubah UseRawInput=1.',
          'Buka Game -> Tekan F2 untuk masuk Test Menu -> Gun Calibration jika bidikan meleset.',
          'Bidikan akan 100% presisi mengikuti crosshair CameraGun AI.'
        ],
        configCode: `[Input]
UseRawInput=1
RawInputMouseCount=1
AutoCenterGun=0`
      }
    };

    this.initElements();
    this.bindEvents();
    this.renderEmulator('teknoparrot');
  }

  initElements() {
    this.tabBtns = document.querySelectorAll('.emu-tab-btn');
    this.guideTitle = document.getElementById('emuGuideTitle');
    this.guideModeBadge = document.getElementById('emuGuideMode');
    this.guideDesc = document.getElementById('emuGuideDesc');
    this.guideStepsList = document.getElementById('emuGuideSteps');
    this.guideCodeBlock = document.getElementById('emuConfigCode');
    this.btnCopyCode = document.getElementById('btnCopyConfigCode');
    this.gunActionRows = document.querySelectorAll('.gun-action-row');
  }

  bindEvents() {
    this.tabBtns.forEach(btn => {
      btn.addEventListener('click', () => {
        const emuKey = btn.getAttribute('data-emu');
        if (emuKey && this.emulatorData[emuKey]) {
          this.tabBtns.forEach(b => b.classList.remove('active'));
          btn.classList.add('active');
          this.renderEmulator(emuKey);
          if (window.cyberAudio) window.cyberAudio.playBeep(1100, 'sine', 0.04);
        }
      });
    });

    if (this.btnCopyCode) {
      this.btnCopyCode.addEventListener('click', () => {
        if (!this.guideCodeBlock) return;
        navigator.clipboard.writeText(this.guideCodeBlock.textContent).then(() => {
          const oldTxt = this.btnCopyCode.textContent;
          this.btnCopyCode.textContent = 'COPIED!';
          this.btnCopyCode.style.color = '#00ff88';
          setTimeout(() => {
            this.btnCopyCode.textContent = oldTxt;
            this.btnCopyCode.style.color = '';
          }, 2000);
        });
      });
    }

    // Interactive button highlights
    this.gunActionRows.forEach(row => {
      row.addEventListener('mouseenter', () => {
        if (window.cyberAudio) window.cyberAudio.playBeep(1300, 'sine', 0.02, 0.05);
      });
    });
  }

  renderEmulator(key) {
    const data = this.emulatorData[key];
    if (!data) return;

    if (this.guideTitle) this.guideTitle.textContent = data.title;
    if (this.guideModeBadge) this.guideModeBadge.textContent = data.mode;
    if (this.guideDesc) this.guideDesc.textContent = data.desc;

    if (this.guideStepsList) {
      this.guideStepsList.innerHTML = data.guide.map(s => `<li>${s}</li>`).join('');
    }

    if (this.guideCodeBlock) {
      this.guideCodeBlock.textContent = data.configCode;
    }
  }
}

window.addEventListener('DOMContentLoaded', () => {
  window.emulatorGuide = new EmulatorGuideManager();
});
