/**
 * RaikaAI Hub - Main Script
 * Same interaction patterns as the portfolio reference:
 * theme toggle, scroll progress, mobile drawer, reveal, toasts.
 * Plus gallery logic: manifest load, search, detail modal, download/copy.
 */

document.addEventListener('DOMContentLoaded', () => {
  initThemeToggle();
  initScrollProgress();
  initSmoothScrollToTop();
  initScrollReveal();
  initMobileMenu();
  initActiveNavObserver();
  initDynamicYear();
  initGallery();
  initCopySample();
  initUploader();
  initAvatarMenu();
});

/* iconType (aplikasi) -> glyph avatar (galeri) */
const ICON_GLYPHS = {
  general: '\u2728',
  coder: '\uD83D\uDCBB',
  writer: '\u270D\uFE0F',
  translator: '\uD83C\uDF10',
  reasoning: '\uD83E\uDDE0',
  speed: '\u26A1',
  shell: '⌨️',
  assistant: '\uD83E\uDD16',
  summarizer: '\uD83D\uDCDD',
  custom: '\uD83C\uDFAD',
};

function glyphFor(iconType) {
  return ICON_GLYPHS[iconType] || ICON_GLYPHS.custom;
}

/* ==========================================================================
   THEME TOGGLE & CIRCULAR VIEW TRANSITIONS
   ========================================================================== */
function initThemeToggle() {
  const themeToggleBtn = document.getElementById('theme-toggle');
  const root = document.documentElement;
  let isTransitioning = false;

  function getEffectiveTheme() {
    const customTheme = root.getAttribute('data-theme');
    if (customTheme === 'dark' || customTheme === 'light') {
      return customTheme;
    }
    return window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
  }

  function applyTheme(theme, showNotice = false) {
    root.setAttribute('data-theme', theme);
    localStorage.setItem('raikaai-hub-theme', theme);

    const metaColorScheme = document.querySelector('meta[name="color-scheme"]');
    if (metaColorScheme) {
      metaColorScheme.content = theme === 'dark' ? 'dark' : 'light';
    }

    if (showNotice) {
      showToast(theme === 'dark' ? 'Mode Gelap diaktifkan' : 'Mode Terang diaktifkan');
    }
  }

  async function toggleTheme(showNotice = false, event = null) {
    if (isTransitioning) return;

    const current = getEffectiveTheme();
    const nextTheme = current === 'dark' ? 'light' : 'dark';

    const prefersReducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    const supportsViewTransition = typeof document.startViewTransition === 'function' && !prefersReducedMotion;

    if (!supportsViewTransition) {
      applyTheme(nextTheme, showNotice);
      return;
    }

    isTransitioning = true;

    let x = window.innerWidth - 40;
    let y = 30;

    if (themeToggleBtn) {
      const rect = themeToggleBtn.getBoundingClientRect();
      x = rect.left + rect.width / 2;
      y = rect.top + rect.height / 2;
    } else if (event && event.clientX) {
      x = event.clientX;
      y = event.clientY;
    }

    const endRadius = Math.hypot(
      Math.max(x, window.innerWidth - x),
      Math.max(y, window.innerHeight - y)
    );

    const isOpening = nextTheme === 'light';
    const transitionType = isOpening ? 'expand' : 'shrink';
    root.setAttribute('data-theme-transition', transitionType);

    try {
      const transition = document.startViewTransition(() => {
        applyTheme(nextTheme, showNotice);
      });

      await transition.ready;

      const clipPathFrames = isOpening
        ? [
            `circle(0px at ${x}px ${y}px)`,
            `circle(${endRadius}px at ${x}px ${y}px)`
          ]
        : [
            `circle(${endRadius}px at ${x}px ${y}px)`,
            `circle(0px at ${x}px ${y}px)`
          ];

      const animation = document.documentElement.animate(
        { clipPath: clipPathFrames },
        {
          duration: 480,
          easing: 'cubic-bezier(0.25, 1, 0.5, 1)',
          pseudoElement: isOpening
            ? '::view-transition-new(root)'
            : '::view-transition-old(root)'
        }
      );

      await animation.finished;
    } catch (err) {
      console.warn('View Transition fallback triggered:', err);
      applyTheme(nextTheme, showNotice);
    } finally {
      root.removeAttribute('data-theme-transition');
      isTransitioning = false;
    }
  }

  if (themeToggleBtn) {
    themeToggleBtn.addEventListener('click', (e) => toggleTheme(false, e));
  }

  // Keyboard shortcut: press 't' to toggle theme (when not typing in form inputs)
  document.addEventListener('keydown', (e) => {
    const activeEl = document.activeElement;
    const isEditing = activeEl && (
      activeEl.tagName === 'INPUT' ||
      activeEl.tagName === 'TEXTAREA' ||
      activeEl.isContentEditable
    );

    if (!isEditing && !e.ctrlKey && !e.metaKey && !e.altKey && e.key.toLowerCase() === 't') {
      e.preventDefault();
      toggleTheme(true);
    }
  });

  // React to OS theme changes if user hasn't explicitly set a preference
  window.matchMedia('(prefers-color-scheme: dark)').addEventListener('change', () => {
    const savedTheme = localStorage.getItem('raikaai-hub-theme');
    if (!savedTheme || savedTheme === 'auto') {
      root.setAttribute('data-theme', 'auto');
    }
  });
}

/* ==========================================================================
   SCROLL PROGRESS INDICATOR
   ========================================================================== */
function initScrollProgress() {
  const progressBar = document.getElementById('scroll-progress');
  if (!progressBar) return;

  let ticking = false;

  function updateProgress() {
    const scrollTop = window.scrollY || document.documentElement.scrollTop;
    const scrollHeight = document.documentElement.scrollHeight - window.innerHeight;
    const progress = scrollHeight > 0 ? (scrollTop / scrollHeight) * 100 : 0;
    progressBar.style.width = `${Math.min(100, Math.max(0, progress))}%`;
    ticking = false;
  }

  window.addEventListener('scroll', () => {
    if (!ticking) {
      window.requestAnimationFrame(updateProgress);
      ticking = true;
    }
  }, { passive: true });

  updateProgress();
}

/* ==========================================================================
   TOAST NOTIFICATION COMPONENT
   ========================================================================== */
function showToast(message, duration = 3500) {
  const container = document.getElementById('toast-container');
  if (!container) return;

  const toast = document.createElement('div');
  toast.className = 'toast';

  const label = document.createElement('span');
  label.textContent = message;

  const iconNS = 'http://www.w3.org/2000/svg';
  const svg = document.createElementNS(iconNS, 'svg');
  svg.setAttribute('class', 'toast-icon');
  svg.setAttribute('width', '16');
  svg.setAttribute('height', '16');
  svg.setAttribute('viewBox', '0 0 24 24');
  svg.setAttribute('fill', 'none');
  svg.setAttribute('stroke', 'currentColor');
  svg.setAttribute('stroke-width', '2');
  svg.setAttribute('stroke-linecap', 'round');
  svg.setAttribute('stroke-linejoin', 'round');
  const polyline = document.createElementNS(iconNS, 'polyline');
  polyline.setAttribute('points', '20 6 9 17 4 12');
  svg.appendChild(polyline);

  toast.appendChild(svg);
  toast.appendChild(label);
  container.appendChild(toast);

  requestAnimationFrame(() => {
    toast.classList.add('show');
  });

  setTimeout(() => {
    toast.classList.remove('show');
    setTimeout(() => {
      if (toast.parentNode === container) {
        container.removeChild(toast);
      }
    }, 250);
  }, duration);
}

function copyText(text, successMessage) {
  if (navigator.clipboard && window.isSecureContext) {
    navigator.clipboard.writeText(text).then(() => {
      showToast(successMessage);
    }).catch(() => {
      fallbackCopy(text, successMessage);
    });
  } else {
    fallbackCopy(text, successMessage);
  }
}

function fallbackCopy(text, successMessage) {
  const tempInput = document.createElement('textarea');
  tempInput.value = text;
  tempInput.style.position = 'fixed';
  tempInput.style.left = '-9999px';
  document.body.appendChild(tempInput);
  tempInput.select();
  try {
    document.execCommand('copy');
    showToast(successMessage);
  } catch {
    showToast('Gagal menyalin ke clipboard.');
  }
  document.body.removeChild(tempInput);
}

/* ==========================================================================
   MOBILE NAVIGATION
   ========================================================================== */
function initMobileMenu() {
  const mobileToggle = document.getElementById('mobile-toggle');
  const mobileNav = document.getElementById('mobile-nav');
  const mobileLinks = document.querySelectorAll('.mobile-nav-link');

  if (!mobileToggle || !mobileNav) return;

  function toggleMenu(forceClose = false) {
    const isOpen = forceClose ? false : !mobileNav.classList.contains('open');
    mobileNav.classList.toggle('open', isOpen);
    mobileToggle.setAttribute('aria-expanded', String(isOpen));
  }

  mobileToggle.addEventListener('click', (e) => {
    e.stopPropagation();
    toggleMenu();
  });

  mobileLinks.forEach(link => {
    link.addEventListener('click', () => toggleMenu(true));
  });

  document.addEventListener('click', (e) => {
    if (!mobileNav.contains(e.target) && !mobileToggle.contains(e.target)) {
      toggleMenu(true);
    }
  });

  document.addEventListener('keydown', (e) => {
    if (e.key === 'Escape' && mobileNav.classList.contains('open')) {
      toggleMenu(true);
    }
  });
}

/* ==========================================================================
   ACTIVE NAVIGATION OBSERVER
   ========================================================================== */
function initActiveNavObserver() {
  const sections = document.querySelectorAll('section[id]');
  const navLinks = document.querySelectorAll('.nav-link');

  if (!sections.length || !navLinks.length) return;

  const observerOptions = {
    root: null,
    rootMargin: '-20% 0px -70% 0px',
    threshold: 0
  };

  const observer = new IntersectionObserver((entries) => {
    entries.forEach(entry => {
      if (entry.isIntersecting) {
        const currentId = entry.target.getAttribute('id');
        navLinks.forEach(link => {
          const href = link.getAttribute('href').replace('#', '');
          link.classList.toggle('active', href === currentId);
        });
      }
    });
  }, observerOptions);

  sections.forEach(section => observer.observe(section));
}

/* ==========================================================================
   DYNAMIC YEAR IN FOOTER
   ========================================================================== */
function initDynamicYear() {
  const yearEl = document.getElementById('year');
  if (yearEl) {
    yearEl.textContent = new Date().getFullYear();
  }
}

/* ==========================================================================
   SMOOTH SCROLL TO TOP
   ========================================================================== */
function initSmoothScrollToTop() {
  const topTriggers = document.querySelectorAll('a[href="#top"], .brand-logo');

  topTriggers.forEach(trigger => {
    trigger.addEventListener('click', (e) => {
      e.preventDefault();

      window.scrollTo({
        top: 0,
        left: 0,
        behavior: 'smooth'
      });

      if (window.location.hash) {
        history.replaceState(null, '', window.location.pathname + window.location.search);
      }
    });
  });
}

/* ==========================================================================
   SCROLL REVEAL
   ========================================================================== */
function initScrollReveal() {
  const revealElements = document.querySelectorAll('.reveal-on-scroll');
  if (!revealElements.length) return;

  if (window.matchMedia('(prefers-reduced-motion: reduce)').matches) {
    revealElements.forEach(el => el.classList.add('is-revealed'));
    return;
  }

  const observerOptions = {
    root: null,
    rootMargin: '0px 0px -40px 0px',
    threshold: 0.08
  };

  const observer = new IntersectionObserver((entries, obs) => {
    entries.forEach(entry => {
      if (entry.isIntersecting) {
        entry.target.classList.add('is-revealed');
        obs.unobserve(entry.target);
      }
    });
  }, observerOptions);

  revealElements.forEach(el => observer.observe(el));
}

/* ==========================================================================
   CHARACTER GALLERY
   ========================================================================== */
function initGallery() {
  const grid = document.getElementById('characters-grid');
  const searchInput = document.getElementById('search-input');
  const searchCount = document.getElementById('search-count');
  const emptyState = document.getElementById('empty-state');
  const statCount = document.getElementById('stat-count');

  if (!grid) return;

  let characters = [];

  function renderRefilter() {
    render(searchInput ? searchInput.value : '');
  }
  galleryListeners.push(renderRefilter);

  function paramText(c) {
    const parts = [];
    if (c.temperature !== null && c.temperature !== undefined) parts.push(`Temp ${c.temperature}`);
    if (c.topK !== null && c.topK !== undefined) parts.push(`Top-K ${c.topK}`);
    parts.push((c.lang || 'en').toUpperCase());
    return parts.join(' · ');
  }

  function matchesQuery(c, q) {
    if (!q) return true;
    const haystack = [
      c.name, c.description, c.author,
      (c.tags || []).join(' '),
    ].join(' ').toLowerCase();
    return q.toLowerCase().split(/\s+/).every(token => haystack.includes(token));
  }

  function render(filter = '') {
    grid.innerHTML = '';
    const pool = allCharacters(characters);
    const visible = pool.filter(c => matchesQuery(c, filter.trim()));

    visible.forEach((c, index) => {
      const card = document.createElement('button');
      card.type = 'button';
      card.className = 'character-card reveal-on-scroll is-revealed';
      card.setAttribute('aria-label', `Lihat detail ${c.name}`);

      const header = document.createElement('div');
      header.className = 'character-header';

      const avatar = document.createElement('div');
      avatar.className = 'character-avatar';
      avatar.textContent = glyphFor(c.iconType);
      avatar.setAttribute('aria-hidden', 'true');

      const titleBlock = document.createElement('div');
      titleBlock.className = 'character-title-block';

      const name = document.createElement('div');
      name.className = 'character-name';
      name.textContent = c.name;

      const author = document.createElement('div');
      author.className = 'character-author';
      author.textContent = `oleh ${c.author || 'Komunitas'}`;

      titleBlock.appendChild(name);
      titleBlock.appendChild(author);
      header.appendChild(avatar);
      header.appendChild(titleBlock);

      const desc = document.createElement('p');
      desc.className = 'character-desc';
      desc.textContent = c.description || 'Tanpa deskripsi.';

      const tags = document.createElement('div');
      tags.className = 'character-tags';
      const shownTags = (c.tags || []).slice();
      if (c._local && !shownTags.includes('lokal')) shownTags.push('lokal');
      shownTags.forEach(t => {
        const tag = document.createElement('span');
        tag.className = 'tag' + (t === 'lokal' ? ' tag-local' : '');
        tag.textContent = t;
        tags.appendChild(tag);
      });

      const meta = document.createElement('div');
      meta.className = 'character-meta tabular';
      meta.textContent = paramText(c);

      card.appendChild(header);
      card.appendChild(desc);
      if (tags.childNodes.length) card.appendChild(tags);
      card.appendChild(meta);

      card.style.transitionDelay = `${Math.min(index, 5) * 60}ms`;
      card.addEventListener('click', () => openModal(c));
      grid.appendChild(card);
    });

    if (searchCount) {
      searchCount.textContent = `${visible.length} dari ${pool.length} karakter`;
    }
    if (emptyState) {
      emptyState.hidden = visible.length > 0;
    }
  }

  if (searchInput) {
    searchInput.addEventListener('input', () => render(searchInput.value));
  }

  fetch('characters/index.json')
    .then(res => {
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      return res.json();
    })
    .then(manifest => Promise.all(
      (manifest.files || []).map(file =>
        fetch(`characters/${file}`).then(res => {
          if (!res.ok) throw new Error(`${file}: HTTP ${res.status}`);
          return res.json();
        })
      )
    ))
    .then(list => {
      characters = list;
      if (statCount) statCount.textContent = String(characters.length);
      render(searchInput ? searchInput.value : '');
    })
    .catch(err => {
      console.error('Gagal memuat galeri karakter:', err);
      grid.innerHTML = '';
      if (emptyState) {
        emptyState.hidden = false;
        emptyState.querySelector('.empty-title').textContent = 'Galeri gagal dimuat';
        emptyState.querySelector('.empty-desc').textContent = 'Jalankan lewat HTTP server lokal (mis. python3 -m http.server) karena fetch tidak bekerja lewat file://.';
      }
    });
}

/* Karakter hasil unggah (hanya sesi ini, tidak masuk galeri). */
const localCharacters = [];
const galleryListeners = [];

function notifyGallery() {
  galleryListeners.forEach(fn => fn());
}

function allCharacters(gallery) {
  return localCharacters.concat(gallery);
}

/* ==========================================================================
   CHARACTER DETAIL MODAL
   ========================================================================== */
let modalCharacter = null;

function renderModalAvatar(c) {
  const avatarEl = document.getElementById('modal-avatar');
  if (!avatarEl) return;
  avatarEl.innerHTML = '';
  const uri = (c.avatarUri || '').trim();
  if (uri) {
    const img = document.createElement('img');
    img.src = uri;
    img.alt = `Ikon ${c.name}`;
    avatarEl.appendChild(img);
  } else {
    avatarEl.textContent = glyphFor(c.iconType);
  }
}

function fieldRow(label, value) {
  if (value === null || value === undefined) return null;
  const text = String(value).trim();
  if (!text) return null;
  const wrapper = document.createElement('div');
  const dt = document.createElement('dt');
  dt.textContent = label;
  const dd = document.createElement('dd');
  dd.textContent = text;
  wrapper.appendChild(dt);
  wrapper.appendChild(dd);
  return wrapper;
}

function openModal(c) {
  modalCharacter = c;

  const backdrop = document.getElementById('modal-backdrop');
  if (!backdrop) return;

  renderModalAvatar(c);

  const previewBadge = document.getElementById('modal-preview-badge');
  if (previewBadge) previewBadge.hidden = !c._local;
  document.getElementById('modal-name').textContent = c.name;
  document.getElementById('modal-author').textContent = `oleh ${c.author || 'Komunitas'} · v${c.schemaVersion || '?'}`;

  const tagsBox = document.getElementById('modal-tags');
  tagsBox.innerHTML = '';
  (c.tags || []).forEach(t => {
    const tag = document.createElement('span');
    tag.className = 'tag';
    tag.textContent = t;
    tagsBox.appendChild(tag);
  });

  document.getElementById('modal-desc').textContent = c.description || 'Tanpa deskripsi.';

  const fields = document.getElementById('modal-fields');
  fields.innerHTML = '';
  [
    fieldRow('Sapaan', c.greeting),
    fieldRow('System Prompt', c.systemPrompt),
    fieldRow('Kepribadian', c.personality),
    fieldRow('Skenario', c.scenario),
    fieldRow('Contoh Dialog', c.exampleDialogues),
    fieldRow('Temperature', c.temperature),
    fieldRow('Top-K', c.topK),
  ].forEach(row => {
    if (row) fields.appendChild(row);
  });

  backdrop.hidden = false;
  requestAnimationFrame(() => backdrop.classList.add('open'));
  document.body.style.overflow = 'hidden';
  document.getElementById('modal-close').focus();
}

function closeModal() {
  const backdrop = document.getElementById('modal-backdrop');
  if (!backdrop || backdrop.hidden) return;

  backdrop.classList.remove('open');
  document.body.style.overflow = '';
  setTimeout(() => {
    backdrop.hidden = true;
  }, 200);
  modalCharacter = null;
}

function downloadModalJson() {
  if (!modalCharacter) return;
  const payload = { ...modalCharacter };
  delete payload._local;
  const uri = (payload.avatarUri || '').trim();
  if (uri.startsWith('data:')) {
    // Aturan galeri: avatarUri harus kosong agar berkas layak di-PR-kan.
    payload.avatarUri = '';
    showToast('Avatar kustom tidak ikut terunduh (aturan galeri).');
  }
  const blob = new Blob([JSON.stringify(payload, null, 2)], { type: 'application/json' });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = `${payload.id || 'character'}.json`;
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  URL.revokeObjectURL(url);
  showToast(`${payload.name} diunduh.`);
}

document.addEventListener('DOMContentLoaded', () => {
  const backdrop = document.getElementById('modal-backdrop');
  if (backdrop) {
    backdrop.addEventListener('click', (e) => {
      if (e.target === backdrop) closeModal();
    });
  }

  const closeBtn = document.getElementById('modal-close');
  if (closeBtn) closeBtn.addEventListener('click', closeModal);

  const downloadBtn = document.getElementById('modal-download');
  if (downloadBtn) downloadBtn.addEventListener('click', downloadModalJson);

  const copyBtn = document.getElementById('modal-copy');
  if (copyBtn) {
    copyBtn.addEventListener('click', () => {
      if (!modalCharacter) return;
      const payload = { ...modalCharacter };
      delete payload._local;
      copyText(JSON.stringify(payload, null, 2), 'JSON tersalin ke clipboard.');
    });
  }

  document.addEventListener('keydown', (e) => {
    const bd = document.getElementById('modal-backdrop');
    if (e.key === 'Escape' && bd && !bd.hidden) closeModal();
  });
});

/* ==========================================================================
   JSON UPLOAD (HERO DROPZONE) — pratinjau lokal, langsung bisa salin/unduh
   ========================================================================== */
function slugify(text) {
  return (text || '')
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '')
    .slice(0, 48) || `karakter-${Date.now()}`;
}

function normalizeUpload(data, fileName) {
  if (!data || typeof data !== 'object' || Array.isArray(data)) {
    throw new Error('Berkas bukan objek JSON karakter.');
  }
  if (!String(data.name || '').trim() || !String(data.systemPrompt || '').trim()) {
    throw new Error('Field wajib: name dan systemPrompt.');
  }
  const tags = Array.isArray(data.tags) ? data.tags.map(String) : [];
  return {
    schemaVersion: 1,
    id: String(data.id || slugify(data.name)).trim() || slugify(fileName),
    name: String(data.name).trim(),
    iconType: String(data.iconType || 'custom').trim() || 'custom',
    description: String(data.description || ''),
    greeting: String(data.greeting || ''),
    systemPrompt: String(data.systemPrompt),
    avatarUri: String(data.avatarUri || ''),
    temperature: data.temperature ?? null,
    topK: data.topK ?? null,
    personality: String(data.personality || ''),
    scenario: String(data.scenario || ''),
    exampleDialogues: String(data.exampleDialogues || ''),
    author: String(data.author || 'Perangkatmu'),
    lang: String(data.lang || 'id'),
    tags,
    _local: true,
  };
}

function initUploader() {
  const dropzone = document.getElementById('upload-dropzone');
  const input = document.getElementById('upload-input');
  if (!dropzone || !input) return;

  function handleFile(file) {
    if (!file) return;
    const reader = new FileReader();
    reader.onload = () => {
      try {
        const data = JSON.parse(reader.result);
        const character = normalizeUpload(data, file.name.replace(/\.json$/i, ''));
        const existing = localCharacters.findIndex(c => c.id === character.id);
        if (existing >= 0) {
          localCharacters[existing] = character;
        } else {
          localCharacters.unshift(character);
        }
        notifyGallery();
        openModal(character);
        showToast(`${character.name} siap dipratinjau.`);
      } catch (err) {
        console.warn('Upload JSON ditolak:', err);
        showToast(err.message || 'JSON tidak valid.');
      }
    };
    reader.onerror = () => showToast('Gagal membaca berkas.');
    reader.readAsText(file);
  }

  dropzone.addEventListener('click', () => input.click());
  dropzone.addEventListener('keydown', (e) => {
    if (e.key === 'Enter' || e.key === ' ') {
      e.preventDefault();
      input.click();
    }
  });
  input.addEventListener('change', () => {
    handleFile(input.files[0]);
    input.value = '';
  });

  ['dragenter', 'dragover'].forEach(evt => {
    dropzone.addEventListener(evt, (e) => {
      e.preventDefault();
      dropzone.classList.add('dragover');
    });
  });
  ['dragleave', 'drop'].forEach(evt => {
    dropzone.addEventListener(evt, (e) => {
      e.preventDefault();
      dropzone.classList.remove('dragover');
    });
  });
  dropzone.addEventListener('drop', (e) => {
    const file = e.dataTransfer && e.dataTransfer.files && e.dataTransfer.files[0];
    handleFile(file);
  });
}

/* ==========================================================================
   AVATAR MENU — klik avatar di modal: unggah gambar / unduh ikon
   ========================================================================== */
function downscaleImage(file, maxSize = 192) {
  return new Promise((resolve, reject) => {
    const url = URL.createObjectURL(file);
    const img = new Image();
    img.onload = () => {
      const scale = Math.min(1, maxSize / Math.max(img.width, img.height));
      const w = Math.max(1, Math.round(img.width * scale));
      const h = Math.max(1, Math.round(img.height * scale));
      const canvas = document.createElement('canvas');
      canvas.width = w;
      canvas.height = h;
      canvas.getContext('2d').drawImage(img, 0, 0, w, h);
      URL.revokeObjectURL(url);
      resolve(canvas.toDataURL('image/png'));
    };
    img.onerror = () => {
      URL.revokeObjectURL(url);
      reject(new Error('Gambar tidak bisa dibaca.'));
    };
    img.src = url;
  });
}

function downloadGlyphPng(glyph, fileName) {
  const size = 512;
  const canvas = document.createElement('canvas');
  canvas.width = size;
  canvas.height = size;
  const ctx = canvas.getContext('2d');
  ctx.font = `${Math.floor(size * 0.72)}px "Segoe UI Emoji", "Noto Color Emoji", "Apple Color Emoji", sans-serif`;
  ctx.textAlign = 'center';
  ctx.textBaseline = 'middle';
  ctx.fillText(glyph, size / 2, size / 2 + size * 0.04);
  canvas.toBlob((blob) => {
    if (!blob) {
      showToast('Gagal membuat PNG.');
      return;
    }
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = fileName;
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    URL.revokeObjectURL(url);
    showToast('Ikon diunduh sebagai PNG.');
  }, 'image/png');
}

function initAvatarMenu() {
  const avatarBtn = document.getElementById('modal-avatar');
  const menu = document.getElementById('avatar-menu');
  const uploadBtn = document.getElementById('avatar-upload-btn');
  const downloadBtn = document.getElementById('avatar-download-btn');
  const fileInput = document.getElementById('avatar-input');
  if (!avatarBtn || !menu || !uploadBtn || !downloadBtn || !fileInput) return;

  function setMenu(open) {
    menu.hidden = !open;
  }

  avatarBtn.addEventListener('click', (e) => {
    e.stopPropagation();
    setMenu(menu.hidden);
  });

  document.addEventListener('click', (e) => {
    if (!menu.hidden && !menu.contains(e.target)) setMenu(false);
  });

  uploadBtn.addEventListener('click', () => {
    setMenu(false);
    fileInput.click();
  });

  fileInput.addEventListener('change', async () => {
    const file = fileInput.files[0];
    fileInput.value = '';
    if (!file || !modalCharacter) return;
    if (!file.type.startsWith('image/')) {
      showToast('Pilih berkas gambar.');
      return;
    }
    try {
      modalCharacter.avatarUri = await downscaleImage(file);
      renderModalAvatar(modalCharacter);
      notifyGallery();
      showToast('Ikon karakter diganti (hanya sesi ini).');
    } catch (err) {
      console.warn('Upload ikon gagal:', err);
      showToast('Gambar tidak bisa dibaca.');
    }
  });

  downloadBtn.addEventListener('click', () => {
    setMenu(false);
    if (!modalCharacter) return;
    const base = modalCharacter.id || 'character';
    const uri = (modalCharacter.avatarUri || '').trim();
    if (uri.startsWith('data:image/')) {
      const ext = uri.startsWith('data:image/jpeg') ? 'jpg' : 'png';
      const a = document.createElement('a');
      a.href = uri;
      a.download = `${base}-icon.${ext}`;
      document.body.appendChild(a);
      a.click();
      document.body.removeChild(a);
      showToast('Ikon diunduh.');
    } else {
      downloadGlyphPng(glyphFor(modalCharacter.iconType), `${base}-icon.png`);
    }
  });
}
/* ==========================================================================
   COPY FORMAT TEMPLATE
   ========================================================================== */
function initCopySample() {
  const btn = document.getElementById('copy-sample-btn');
  const sample = document.getElementById('format-sample');
  if (!btn || !sample) return;

  btn.addEventListener('click', () => {
    copyText(sample.innerText.trim(), 'Template JSON tersalin.');
  });
}
