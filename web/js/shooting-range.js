/**
 * Interactive Arcade Shooting Range Mini-Game for CameraGun AI
 * Realistic recoil, particle effects, off-screen reload, audio integration
 */

class ShootingRangeGame {
  constructor() {
    this.canvas = document.getElementById('shootingCanvas');
    if (!this.canvas) return;
    this.ctx = this.canvas.getContext('2d');

    // Stats
    this.maxAmmo = 6;
    this.ammo = 6;
    this.score = 0;
    this.shotsFired = 0;
    this.shotsHit = 0;
    this.streak = 0;
    this.lastShotTime = performance.now();
    this.reactionTimes = [];

    // Game loop & targets
    this.targets = [];
    this.particles = [];
    this.floatingTexts = [];
    this.lastSpawn = 0;
    this.spawnInterval = 1400; // ms
    this.recoilOffset = { x: 0, y: 0 };
    this.mouseX = 0;
    this.mouseY = 0;
    this.isMouseInside = false;

    this.initCanvasSize();
    this.initUI();
    this.bindEvents();

    // Start loop
    this.isRunning = true;
    requestAnimationFrame((t) => this.loop(t));
  }

  initCanvasSize() {
    const rect = this.canvas.getBoundingClientRect();
    this.canvas.width = rect.width || 800;
    this.canvas.height = rect.height || 520;
    window.addEventListener('resize', () => {
      const r = this.canvas.getBoundingClientRect();
      this.canvas.width = r.width;
      this.canvas.height = r.height;
    });
  }

  initUI() {
    this.scoreValEl = document.getElementById('rangeScoreVal');
    this.accuracyValEl = document.getElementById('rangeAccuracyVal');
    this.streakValEl = document.getElementById('rangeStreakVal');
    this.reactionValEl = document.getElementById('rangeReactionVal');
    this.ammoContainer = document.getElementById('rangeAmmoContainer');
    this.reloadZoneEl = document.getElementById('rangeReloadZone');

    this.updateAmmoUI();
  }

  bindEvents() {
    this.canvas.addEventListener('mousemove', (e) => {
      const rect = this.canvas.getBoundingClientRect();
      this.mouseX = e.clientX - rect.left;
      this.mouseY = e.clientY - rect.top;
      this.isMouseInside = true;
    });

    this.canvas.addEventListener('mouseenter', () => {
      this.isMouseInside = true;
    });

    // Off-screen reload trigger when moving out
    this.canvas.addEventListener('mouseleave', () => {
      this.isMouseInside = false;
      if (this.ammo < this.maxAmmo) {
        this.reload();
      }
    });

    // Shoot
    this.canvas.addEventListener('mousedown', (e) => {
      if (e.button === 0) { // Left click
        this.shoot();
      } else if (e.button === 2) { // Right click -> Reload
        e.preventDefault();
        this.reload();
      }
    });

    this.canvas.addEventListener('contextmenu', (e) => e.preventDefault());

    // Manual reload button
    if (this.reloadZoneEl) {
      this.reloadZoneEl.addEventListener('click', () => this.reload());
    }

    // Keyboard support: R or Space for reload
    window.addEventListener('keydown', (e) => {
      if (e.key === 'r' || e.key === 'R' || e.code === 'Space') {
        if (this.isMouseInside) {
          e.preventDefault();
          this.reload();
        }
      }
    });
  }

  shoot() {
    if (this.ammo <= 0) {
      if (window.cyberAudio) window.cyberAudio.playEmptyClip();
      this.addFloatingText(this.mouseX, this.mouseY - 10, 'EMPTY! RELOAD', '#ff006e');
      return;
    }

    this.ammo--;
    this.shotsFired++;
    this.updateAmmoUI();

    // Trigger audio
    if (window.cyberAudio) window.cyberAudio.playBlaster();

    // Recoil kick
    this.recoilOffset.y = -8;
    this.recoilOffset.x = (Math.random() - 0.5) * 6;

    // Hit detection
    let hit = false;
    const now = performance.now();

    for (let i = this.targets.length - 1; i >= 0; i--) {
      const t = this.targets[i];
      const dist = Math.hypot(this.mouseX - t.x, this.mouseY - t.y);

      if (dist <= t.radius) {
        hit = true;
        this.shotsHit++;
        this.streak++;

        // Calculate reaction time
        const reaction = Math.round(now - t.spawnTime);
        this.reactionTimes.push(reaction);

        let pts = t.points * Math.min(this.streak, 5);
        if (t.type === 'hostage') {
          pts = -200;
          this.streak = 0;
          if (window.cyberAudio) window.cyberAudio.playEmptyClip();
        } else {
          if (window.cyberAudio) window.cyberAudio.playHit();
        }

        this.score = Math.max(0, this.score + pts);

        // Spawn spark explosion
        this.spawnSparks(t.x, t.y, t.color);

        // Floating points
        const txt = pts > 0 ? `+${pts}` : `${pts}`;
        this.addFloatingText(t.x, t.y, txt, t.color);

        // Remove target
        this.targets.splice(i, 1);
        break;
      }
    }

    if (!hit) {
      this.streak = 0;
      // Miss spark on canvas
      this.spawnSparks(this.mouseX, this.mouseY, '#64748b', 6);
    }

    this.updateStatsUI();
  }

  reload() {
    if (this.ammo === this.maxAmmo) return;
    this.ammo = this.maxAmmo;
    this.updateAmmoUI();
    if (window.cyberAudio) window.cyberAudio.playReload();
    this.addFloatingText(this.canvas.width / 2, this.canvas.height - 60, 'RELOADED (6/6)', '#00ff88');
  }

  spawnSparks(x, y, color, count = 16) {
    for (let i = 0; i < count; i++) {
      const angle = Math.random() * Math.PI * 2;
      const speed = Math.random() * 5 + 2;
      this.particles.push({
        x,
        y,
        vx: Math.cos(angle) * speed,
        vy: Math.sin(angle) * speed,
        radius: Math.random() * 3 + 1.5,
        color,
        alpha: 1,
        life: 0.94
      });
    }
  }

  addFloatingText(x, y, text, color) {
    this.floatingTexts.push({
      x,
      y,
      text,
      color,
      alpha: 1,
      vy: -1.4
    });
  }

  updateAmmoUI() {
    if (!this.ammoContainer) return;
    this.ammoContainer.innerHTML = '';
    for (let i = 0; i < this.maxAmmo; i++) {
      const b = document.createElement('div');
      b.className = `bullet-icon ${i < this.ammo ? '' : 'spent'}`;
      this.ammoContainer.appendChild(b);
    }
  }

  updateStatsUI() {
    if (this.scoreValEl) this.scoreValEl.textContent = this.score.toLocaleString();
    if (this.streakValEl) this.streakValEl.textContent = `${this.streak}x`;
    if (this.accuracyValEl) {
      const acc = this.shotsFired > 0 ? Math.round((this.shotsHit / this.shotsFired) * 100) : 100;
      this.accuracyValEl.textContent = `${acc}%`;
    }
    if (this.reactionValEl && this.reactionTimes.length > 0) {
      const avg = Math.round(this.reactionTimes.reduce((a, b) => a + b, 0) / this.reactionTimes.length);
      this.reactionValEl.textContent = `${avg}ms`;
    }
  }

  spawnTarget(timestamp) {
    if (timestamp - this.lastSpawn < this.spawnInterval) return;
    if (this.targets.length >= 4) return;

    this.lastSpawn = timestamp;

    const types = [
      { type: 'drone', radius: 28, points: 100, color: '#00e5ff', speed: 2.2 },
      { type: 'drone', radius: 28, points: 100, color: '#00e5ff', speed: 2.5 },
      { type: 'bullseye', radius: 20, points: 250, color: '#ffbe0b', speed: 3.2 },
      { type: 'hostage', radius: 26, points: -200, color: '#ff006e', speed: 1.8 }
    ];

    const pick = types[Math.floor(Math.random() * types.length)];
    const margin = 60;
    const x = Math.random() * (this.canvas.width - margin * 2) + margin;
    const y = Math.random() * (this.canvas.height - margin * 2 - 80) + margin;
    const direction = Math.random() > 0.5 ? 1 : -1;

    this.targets.push({
      ...pick,
      x,
      y,
      baseY: y,
      vx: pick.speed * direction,
      vy: 0,
      phase: Math.random() * Math.PI * 2,
      spawnTime: performance.now(),
      created: timestamp
    });
  }

  loop(timestamp) {
    if (!this.isRunning) return;

    this.spawnTarget(timestamp);

    // Ease recoil back to 0
    this.recoilOffset.x *= 0.75;
    this.recoilOffset.y *= 0.75;

    // Clear canvas
    this.ctx.clearRect(0, 0, this.canvas.width, this.canvas.height);

    // Draw Cyber Grid & Crosshair backdrop
    this.drawBackdrop();

    // Update & draw targets
    this.updateAndDrawTargets(timestamp);

    // Update & draw particles
    this.updateAndDrawParticles();

    // Update & draw floating text
    this.updateAndDrawTexts();

    // Draw player crosshair if mouse inside
    if (this.isMouseInside) {
      this.drawCrosshair(this.mouseX + this.recoilOffset.x, this.mouseY + this.recoilOffset.y);
    }

    requestAnimationFrame((t) => this.loop(t));
  }

  drawBackdrop() {
    this.ctx.save();
    this.ctx.strokeStyle = 'rgba(0, 229, 255, 0.05)';
    this.ctx.lineWidth = 1;

    // Horizontal grid
    for (let y = 0; y < this.canvas.height; y += 40) {
      this.ctx.beginPath();
      this.ctx.moveTo(0, y);
      this.ctx.lineTo(this.canvas.width, y);
      this.ctx.stroke();
    }
    // Vertical grid
    for (let x = 0; x < this.canvas.width; x += 40) {
      this.ctx.beginPath();
      this.ctx.moveTo(x, 0);
      this.ctx.lineTo(x, this.canvas.height);
      this.ctx.stroke();
    }

    // Border glowing line
    this.ctx.strokeStyle = 'rgba(0, 229, 255, 0.3)';
    this.ctx.lineWidth = 2;
    this.ctx.strokeRect(10, 10, this.canvas.width - 20, this.canvas.height - 20);

    this.ctx.restore();
  }

  updateAndDrawTargets(timestamp) {
    for (let i = this.targets.length - 1; i >= 0; i--) {
      const t = this.targets[i];

      // Move horizontally & sine float
      t.x += t.vx;
      t.phase += 0.04;
      t.y = t.baseY + Math.sin(t.phase) * 12;

      // Bounce horizontal walls
      if (t.x - t.radius < 20 || t.x + t.radius > this.canvas.width - 20) {
        t.vx *= -1;
      }

      // Expire target after 7 seconds
      if (timestamp - t.created > 7000) {
        this.targets.splice(i, 1);
        continue;
      }

      this.ctx.save();
      this.ctx.translate(t.x, t.y);

      if (t.type === 'drone') {
        // Outer rotating ring
        this.ctx.strokeStyle = t.color;
        this.ctx.lineWidth = 2;
        this.ctx.beginPath();
        this.ctx.arc(0, 0, t.radius, 0, Math.PI * 2);
        this.ctx.stroke();

        // Inner glowing core
        this.ctx.fillStyle = 'rgba(0, 229, 255, 0.3)';
        this.ctx.beginPath();
        this.ctx.arc(0, 0, t.radius * 0.5, 0, Math.PI * 2);
        this.ctx.fill();

        // Target ticks
        this.ctx.strokeStyle = '#fff';
        this.ctx.beginPath();
        this.ctx.moveTo(-t.radius - 4, 0); this.ctx.lineTo(-t.radius + 4, 0);
        this.ctx.moveTo(t.radius - 4, 0); this.ctx.lineTo(t.radius + 4, 0);
        this.ctx.stroke();

      } else if (t.type === 'bullseye') {
        // High-value bullseye
        this.ctx.strokeStyle = t.color;
        this.ctx.lineWidth = 3;
        this.ctx.beginPath();
        this.ctx.arc(0, 0, t.radius, 0, Math.PI * 2);
        this.ctx.stroke();

        this.ctx.fillStyle = t.color;
        this.ctx.beginPath();
        this.ctx.arc(0, 0, t.radius * 0.4, 0, Math.PI * 2);
        this.ctx.fill();

      } else if (t.type === 'hostage') {
        // Hostage civilian warning
        this.ctx.strokeStyle = t.color;
        this.ctx.lineWidth = 2;
        this.ctx.strokeRect(-t.radius, -t.radius, t.radius * 2, t.radius * 2);

        this.ctx.fillStyle = t.color;
        this.ctx.font = '10px JetBrains Mono';
        this.ctx.textAlign = 'center';
        this.ctx.fillText('DON\'T SHOOT', 0, 4);
      }

      this.ctx.restore();
    }
  }

  updateAndDrawParticles() {
    for (let i = this.particles.length - 1; i >= 0; i--) {
      const p = this.particles[i];
      p.x += p.vx;
      p.y += p.vy;
      p.alpha *= p.life;

      if (p.alpha < 0.05) {
        this.particles.splice(i, 1);
        continue;
      }

      this.ctx.save();
      this.ctx.globalAlpha = p.alpha;
      this.ctx.fillStyle = p.color;
      this.ctx.beginPath();
      this.ctx.arc(p.x, p.y, p.radius, 0, Math.PI * 2);
      this.ctx.fill();
      this.ctx.restore();
    }
  }

  updateAndDrawTexts() {
    for (let i = this.floatingTexts.length - 1; i >= 0; i--) {
      const ft = this.floatingTexts[i];
      ft.y += ft.vy;
      ft.alpha -= 0.02;

      if (ft.alpha <= 0) {
        this.floatingTexts.splice(i, 1);
        continue;
      }

      this.ctx.save();
      this.ctx.globalAlpha = ft.alpha;
      this.ctx.font = 'bold 15px Orbitron';
      this.ctx.fillStyle = ft.color;
      this.ctx.textAlign = 'center';
      this.ctx.shadowColor = ft.color;
      this.ctx.shadowBlur = 10;
      this.ctx.fillText(ft.text, ft.x, ft.y);
      this.ctx.restore();
    }
  }

  drawCrosshair(x, y) {
    this.ctx.save();
    this.ctx.strokeStyle = '#00e5ff';
    this.ctx.lineWidth = 1.5;
    this.ctx.shadowColor = '#00e5ff';
    this.ctx.shadowBlur = 8;

    // Outer circle
    this.ctx.beginPath();
    this.ctx.arc(x, y, 16, 0, Math.PI * 2);
    this.ctx.stroke();

    // Center dot
    this.ctx.fillStyle = '#ff006e';
    this.ctx.beginPath();
    this.ctx.arc(x, y, 2.5, 0, Math.PI * 2);
    this.ctx.fill();

    // 4 Crosshair lines
    this.ctx.beginPath();
    this.ctx.moveTo(x - 24, y); this.ctx.lineTo(x - 18, y);
    this.ctx.moveTo(x + 18, y); this.ctx.lineTo(x + 24, y);
    this.ctx.moveTo(x, y - 24); this.ctx.lineTo(x, y - 18);
    this.ctx.moveTo(x, y + 18); this.ctx.lineTo(x, y + 24);
    this.ctx.stroke();

    this.ctx.restore();
  }
}

window.addEventListener('DOMContentLoaded', () => {
  window.shootingRange = new ShootingRangeGame();
});
