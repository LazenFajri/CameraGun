/**
 * Main Application Orchestrator for CameraGun AI Web Portal
 * Custom Tactical Reticle, Navigation, Telemetry Jitter, SFX Controls
 */

document.addEventListener('DOMContentLoaded', () => {
  // 1. Custom Reticle Cursor
  const reticle = document.getElementById('customReticle');
  let mouseX = window.innerWidth / 2;
  let mouseY = window.innerHeight / 2;
  let reticleX = mouseX;
  let reticleY = mouseY;

  if (reticle) {
    document.addEventListener('mousemove', (e) => {
      mouseX = e.clientX;
      mouseY = e.clientY;
    });

    document.addEventListener('mousedown', () => {
      reticle.classList.add('firing');
    });

    document.addEventListener('mouseup', () => {
      reticle.classList.remove('firing');
    });

    // Reticle lock-on hover on buttons and cards
    const hoverTargets = document.querySelectorAll('button, a, input, .btn-cyber, .card-cyber, .btn-preset');
    hoverTargets.forEach(el => {
      el.addEventListener('mouseenter', () => {
        reticle.classList.add('active');
        if (window.cyberAudio) window.cyberAudio.playBeep(1400, 'sine', 0.015, 0.03);
      });
      el.addEventListener('mouseleave', () => {
        reticle.classList.remove('active');
      });
    });

    // Smooth Lerp loop for reticle
    const renderReticle = () => {
      reticleX += (mouseX - reticleX) * 0.35;
      reticleY += (mouseY - reticleY) * 0.35;
      reticle.style.left = `${reticleX}px`;
      reticle.style.top = `${reticleY}px`;
      requestAnimationFrame(renderReticle);
    };
    requestAnimationFrame(renderReticle);
  }

  // 2. Audio SFX Toggle Button
  const btnToggleAudio = document.getElementById('btnToggleAudio');
  if (btnToggleAudio && window.cyberAudio) {
    const updateAudioButton = () => {
      if (window.cyberAudio.muted) {
        btnToggleAudio.innerHTML = '🔇 SFX: OFF';
        btnToggleAudio.classList.remove('active');
      } else {
        btnToggleAudio.innerHTML = '🔊 SFX: ON';
        btnToggleAudio.classList.add('active');
      }
    };
    updateAudioButton();

    btnToggleAudio.addEventListener('click', () => {
      window.cyberAudio.toggleMute();
      updateAudioButton();
      if (!window.cyberAudio.muted) {
        window.cyberAudio.playBeep(880, 'sine', 0.05);
      }
    });
  }

  // 3. Quick Fullscreen Border from Header
  const btnQuickBorder = document.getElementById('btnNavQuickBorder');
  if (btnQuickBorder && window.borderCalibrator) {
    btnQuickBorder.addEventListener('click', () => {
      window.borderCalibrator.enterFullscreen();
    });
  }

  // 4. Hero Telemetry Fluctuations (Realistic Sensor Noise)
  const heroLatencyEl = document.getElementById('heroLatencyVal');
  const heroFpsEl = document.getElementById('heroFpsVal');

  if (heroLatencyEl || heroFpsEl) {
    setInterval(() => {
      if (heroLatencyEl) {
        const lat = (5.2 + Math.random() * 1.8).toFixed(1);
        heroLatencyEl.innerHTML = `${lat} <span class="telemetry-unit">ms</span>`;
      }
      if (heroFpsEl) {
        const fps = Math.round(118 + Math.random() * 4);
        heroFpsEl.innerHTML = `${fps} <span class="telemetry-unit">FPS</span>`;
      }
    }, 400);
  }

  // 5. Copy Buttons (Generic)
  const copyButtons = document.querySelectorAll('.btn-copy-command');
  copyButtons.forEach(btn => {
    btn.addEventListener('click', () => {
      const textToCopy = btn.getAttribute('data-copy');
      if (textToCopy) {
        navigator.clipboard.writeText(textToCopy).then(() => {
          const original = btn.textContent;
          btn.textContent = 'COPIED!';
          btn.style.color = '#00ff88';
          if (window.cyberAudio) window.cyberAudio.playBeep(1200, 'sine', 0.04);
          setTimeout(() => {
            btn.textContent = original;
            btn.style.color = '';
          }, 2000);
        });
      }
    });
  });

  // 6. Navigation Active Scroll Spy
  const sections = document.querySelectorAll('section[id]');
  const navLinks = document.querySelectorAll('.nav-link[href^="#"]');

  window.addEventListener('scroll', () => {
    let currentId = '';
    const scrollY = window.pageYOffset;

    sections.forEach(sec => {
      const top = sec.offsetTop - 120;
      const height = sec.offsetHeight;
      if (scrollY >= top && scrollY < top + height) {
        currentId = sec.getAttribute('id');
      }
    });

    navLinks.forEach(link => {
      link.classList.remove('active');
      if (link.getAttribute('href') === `#${currentId}`) {
        link.classList.add('active');
      }
    });
  });
});
