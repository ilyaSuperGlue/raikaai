/**
 * RaikaAI Hub - Main Script
 * App-like single screen: tabs (Galeri / Template / Panduan),
 * upload + paste JSON, tag chips, detail modal, avatar menu.
 * Theme, drawer, and toast patterns follow the portfolio reference.
 */

document.addEventListener('DOMContentLoaded', () => {
  initThemeToggle();
  initScrollProgress();
  initSmoothScrollToTop();
  initMobileMenu();
  initDynamicYear();
  initTabs();
  initGallery();
  initUploader();
  initPasteModal();
  initTemplates();
  initAvatarMenu();
  initModalButtons();
});

/* iconType (aplikasi) -> glyph avatar (galeri) */
const ICON_GLYPHS = {
  general: '✨',
  coder: '💻',
  writer: '✍️',
  translator: '🌐',
  reasoning: '🧠',
  speed: '⚡',
  shell: '⌨️',
  assistant: '🤖',
  summarizer: '📝',
  custom: '🎭',
};

function glyphFor(iconType) {
  return ICON_GLYPHS[iconType] || ICON_GLYPHS.custom;
}

/* Karakter hasil unggah/tempel (hanya sesi ini, tidak masuk galeri). */
const localCharacters = [];
const galleryListeners = [];

function notifyGallery() {
  galleryListeners.forEach(fn => fn());
}

function allCharacters(gallery) {
  return localCharacters.concat(gallery);
}

/* ==========================================================================
   TABS
   ========================================================================== */
function switchTab(name) {
  document.querySelectorAll('[data-tab]').forEach(el => {
    const active = el.getAttribute('data-tab') === name;
    el.classList.toggle('is-active', active);
    if (el.getAttribute('role') === 'tab') {
      el.setAttribute('aria-selected', String(active));
    }
  });

  document.querySelectorAll('.tab-view').forEach(view => {
    const active = view.id === `view-${name}`;
    view.hidden = !active;
    view.classList.toggle('is-active', active);
  });
}

function initTabs() {
  document.querySelectorAll('[data-tab]').forEach(el => {
    el.addEventListener('click', () => {
      switchTab(el.getAttribute('data-tab'));
      // Tutup drawer mobile setelah pilih tab
      const mobileNav = document.getElementById('mobile-nav');
      const mobileToggle = document.getElementById('mobile-toggle');
      if (mobileNav && mobileNav.classList.contains('open')) {
        mobileNav.classList.remove('open');
        if (mobileToggle) mobileToggle.setAttribute('aria-expanded', 'false');
      }
      window.scrollTo({ top: 0, behavior: 'smooth' });
    });
  });
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
   CHARACTER GALLERY (search + tag chips + local uploads)
   ========================================================================== */
function initGallery() {
  const grid = document.getElementById('characters-grid');
  const searchInput = document.getElementById('search-input');
  const searchCount = document.getElementById('search-count');
  const emptyState = document.getElementById('empty-state');
  const chipRow = document.getElementById('chip-row');

  if (!grid) return;

  let characters = [];
  let activeTag = '';

  function currentFilter() {
    return {
      query: searchInput ? searchInput.value.trim() : '',
      tag: activeTag,
    };
  }

  function matchesQuery(c, q) {
    if (!q) return true;
    const haystack = [
      c.name, c.description, c.author,
      (c.tags || []).join(' '),
    ].join(' ').toLowerCase();
    return q.toLowerCase().split(/\s+/).every(token => haystack.includes(token));
  }

  function paramText(c) {
    const parts = [];
    if (c.temperature !== null && c.temperature !== undefined) parts.push(`Temp ${c.temperature}`);
    if (c.topK !== null && c.topK !== undefined) parts.push(`Top-K ${c.topK}`);
    parts.push((c.lang || 'en').toUpperCase());
    return parts.join(' · ');
  }

  function renderChips() {
    if (!chipRow) return;
    chipRow.innerHTML = '';

    const tags = new Set();
    allCharacters(characters).forEach(c => (c.tags || []).forEach(t => {
      if (t !== 'lokal') tags.add(t);
    }));

    const allChip = document.createElement('button');
    allChip.type = 'button';
    allChip.className = 'chip' + (activeTag === '' ? ' is-active' : '');
    allChip.textContent = 'Semua';
    allChip.addEventListener('click', () => {
      activeTag = '';
      render();
    });
    chipRow.appendChild(allChip);

    [...tags].sort().forEach(t => {
      const chip = document.createElement('button');
      chip.type = 'button';
      chip.className = 'chip' + (activeTag === t ? ' is-active' : '');
      chip.textContent = t;
      chip.addEventListener('click', () => {
        activeTag = activeTag === t ? '' : t;
        render();
      });
      chipRow.appendChild(chip);
    });
  }

  function render() {
    const { query, tag } = currentFilter();
    grid.innerHTML = '';
    const pool = allCharacters(characters);
    const visible = pool.filter(c => {
      if (tag && !(c.tags || []).includes(tag)) return false;
      return matchesQuery(c, query);
    });

    visible.forEach((c) => {
      const card = document.createElement('button');
      card.type = 'button';
      card.className = 'character-card';
      card.setAttribute('aria-label', `Lihat detail ${c.name}`);

      const header = document.createElement('div');
      header.className = 'character-header';

      const avatar = document.createElement('div');
      avatar.className = 'character-avatar';
      avatar.setAttribute('aria-hidden', 'true');
      const uri = (c.avatarUri || '').trim();
      if (uri) {
        const img = document.createElement('img');
        img.src = uri;
        img.alt = '';
        avatar.appendChild(img);
      } else {
        avatar.textContent = glyphFor(c.iconType);
      }

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

      card.addEventListener('click', () => openModal(c));
      grid.appendChild(card);
    });

    if (searchCount) {
      searchCount.textContent = `${visible.length} dari ${pool.length} karakter`;
    }
    if (emptyState) {
      emptyState.hidden = visible.length > 0;
    }
    renderChips();
  }

  galleryListeners.push(render);

  if (searchInput) {
    searchInput.addEventListener('input', render);
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
      render();
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
  document.getElementById('modal-name').textContent = c.name;
  document.getElementById('modal-author').textContent = `oleh ${c.author || 'Komunitas'} · v${c.schemaVersion || '?'}`;

  const previewBadge = document.getElementById('modal-preview-badge');
  if (previewBadge) previewBadge.hidden = !c._local;

  const tagsBox = document.getElementById('modal-tags');
  tagsBox.innerHTML = '';
  const shownTags = (c.tags || []).slice();
  if (c._local && !shownTags.includes('lokal')) shownTags.push('lokal');
  shownTags.forEach(t => {
    const tag = document.createElement('span');
    tag.className = 'tag' + (t === 'lokal' ? ' tag-local' : '');
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

function closeModal(backdropId) {
  const backdrop = document.getElementById(backdropId || 'modal-backdrop');
  if (!backdrop || backdrop.hidden) return;

  backdrop.classList.remove('open');
  if (document.getElementById('modal-backdrop').hidden || backdropId === 'modal-backdrop') {
    document.body.style.overflow = '';
  }
  setTimeout(() => {
    backdrop.hidden = true;
    if (document.getElementById('modal-backdrop').hidden &&
        document.getElementById('paste-backdrop').hidden) {
      document.body.style.overflow = '';
    }
  }, 200);
  if (!backdropId || backdropId === 'modal-backdrop') modalCharacter = null;
}

function shareableCopy(c) {
  const payload = { ...c };
  delete payload._local;
  return payload;
}

function downloadModalJson() {
  if (!modalCharacter) return;
  const payload = shareableCopy(modalCharacter);
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

function initModalButtons() {
  const backdrop = document.getElementById('modal-backdrop');
  if (backdrop) {
    backdrop.addEventListener('click', (e) => {
      if (e.target === backdrop) closeModal('modal-backdrop');
    });
  }

  const closeBtn = document.getElementById('modal-close');
  if (closeBtn) closeBtn.addEventListener('click', () => closeModal('modal-backdrop'));

  const downloadBtn = document.getElementById('modal-download');
  if (downloadBtn) downloadBtn.addEventListener('click', downloadModalJson);

  const copyBtn = document.getElementById('modal-copy');
  if (copyBtn) {
    copyBtn.addEventListener('click', () => {
      if (!modalCharacter) return;
      copyText(JSON.stringify(shareableCopy(modalCharacter), null, 2), 'JSON tersalin ke clipboard.');
    });
  }

  document.addEventListener('keydown', (e) => {
    if (e.key !== 'Escape') return;
    const pasteBd = document.getElementById('paste-backdrop');
    const modalBd = document.getElementById('modal-backdrop');
    if (pasteBd && !pasteBd.hidden) {
      closeModal('paste-backdrop');
    } else if (modalBd && !modalBd.hidden) {
      const avatarMenu = document.getElementById('avatar-menu');
      if (avatarMenu && !avatarMenu.hidden) {
        avatarMenu.hidden = true;
      } else {
        closeModal('modal-backdrop');
      }
    }
  });
}

/* ==========================================================================
   JSON UPLOAD — tombol, drag-drop halaman, dan validasi
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

function addLocalCharacter(character) {
  const existing = localCharacters.findIndex(c => c.id === character.id);
  if (existing >= 0) {
    localCharacters[existing] = character;
  } else {
    localCharacters.unshift(character);
  }
  notifyGallery();
  switchTab('galeri');
  openModal(character);
  showToast(`${character.name} siap dipratinjau.`);
}

function parseCharacterText(text, fileName) {
  try {
    const data = JSON.parse(text);
    addLocalCharacter(normalizeUpload(data, fileName || 'tempelan'));
  } catch (err) {
    console.warn('JSON ditolak:', err);
    showToast(err.message || 'JSON tidak valid.');
  }
}

function initUploader() {
  const uploadBtn = document.getElementById('upload-btn');
  const input = document.getElementById('upload-input');
  const overlay = document.getElementById('drop-overlay');
  if (!uploadBtn || !input || !overlay) return;

  uploadBtn.addEventListener('click', () => input.click());
  input.addEventListener('change', () => {
    const file = input.files[0];
    input.value = '';
    if (!file) return;
    const reader = new FileReader();
    reader.onload = () => parseCharacterText(reader.result, file.name.replace(/\.json$/i, ''));
    reader.onerror = () => showToast('Gagal membaca berkas.');
    reader.readAsText(file);
  });

  // Drag-drop di seluruh halaman
  let dragDepth = 0;
  window.addEventListener('dragenter', (e) => {
    if (!e.dataTransfer || ![...e.dataTransfer.types].includes('Files')) return;
    e.preventDefault();
    dragDepth += 1;
    overlay.hidden = false;
  });
  window.addEventListener('dragleave', (e) => {
    e.preventDefault();
    dragDepth = Math.max(0, dragDepth - 1);
    if (dragDepth === 0) overlay.hidden = true;
  });
  window.addEventListener('dragover', (e) => e.preventDefault());
  window.addEventListener('drop', (e) => {
    e.preventDefault();
    dragDepth = 0;
    overlay.hidden = true;
    const file = e.dataTransfer && e.dataTransfer.files && e.dataTransfer.files[0];
    if (!file) return;
    const reader = new FileReader();
    reader.onload = () => parseCharacterText(reader.result, file.name.replace(/\.json$/i, ''));
    reader.onerror = () => showToast('Gagal membaca berkas.');
    reader.readAsText(file);
  });
}

/* ==========================================================================
   PASTE JSON MODAL
   ========================================================================== */
function initPasteModal() {
  const pasteBtn = document.getElementById('paste-btn');
  const backdrop = document.getElementById('paste-backdrop');
  const closeBtn = document.getElementById('paste-close');
  const cancelBtn = document.getElementById('paste-cancel');
  const previewBtn = document.getElementById('paste-preview');
  const textarea = document.getElementById('paste-text');
  if (!pasteBtn || !backdrop || !textarea) return;

  function open() {
    backdrop.hidden = false;
    requestAnimationFrame(() => backdrop.classList.add('open'));
    document.body.style.overflow = 'hidden';
    textarea.focus();
  }

  pasteBtn.addEventListener('click', open);
  if (closeBtn) closeBtn.addEventListener('click', () => closeModal('paste-backdrop'));
  if (cancelBtn) cancelBtn.addEventListener('click', () => closeModal('paste-backdrop'));
  backdrop.addEventListener('click', (e) => {
    if (e.target === backdrop) closeModal('paste-backdrop');
  });

  if (previewBtn) {
    previewBtn.addEventListener('click', () => {
      if (!textarea.value.trim()) {
        showToast('Tempel dulu isi JSON-nya.');
        return;
      }
      closeModal('paste-backdrop');
      setTimeout(() => parseCharacterText(textarea.value, 'tempelan'), 200);
    });
  }
}

/* ==========================================================================
   TEMPLATES
   ========================================================================== */
const TEMPLATES = [
  {
    id: 'template-minimal',
    title: 'Minimal',
    desc: 'Hanya field wajib: nama dan system prompt. Titik awal tercepat.',
    data: {
      schemaVersion: 1,
      id: 'id-karakter',
      name: 'Nama Karakter',
      iconType: 'general',
      description: '',
      greeting: '',
      systemPrompt: 'Tulis instruksi perilaku karakter di sini.',
      avatarUri: '',
      temperature: null,
      topK: null,
      personality: '',
      scenario: '',
      exampleDialogues: '',
      author: '',
      lang: 'id',
      tags: [],
    },
  },
  {
    id: 'template-lengkap',
    title: 'Lengkap',
    desc: 'Semua field terisi contoh. Hapus atau ubah yang tidak perlu.',
    data: {
      schemaVersion: 1,
      id: 'id-karakter',
      name: 'Nama Karakter',
      iconType: 'custom',
      description: 'Deskripsi singkat karakter, satu-dua kalimat.',
      greeting: 'Halo! Ada yang bisa saya bantu?',
      systemPrompt: 'Jelaskan siapa karakter ini, gaya bicaranya, dan batasannya.',
      avatarUri: '',
      temperature: 0.7,
      topK: 40,
      personality: 'Sifat dan gaya bicara karakter.',
      scenario: 'Konteks atau latar percakapan.',
      exampleDialogues: 'User: Contoh pertanyaan\nKarakter: Contoh jawaban',
      author: '',
      lang: 'id',
      tags: [],
    },
  },
  {
    id: 'template-roleplay',
    title: 'Roleplay',
    desc: 'Fokus ke kepribadian, skenario, dan contoh dialog untuk main peran.',
    data: {
      schemaVersion: 1,
      id: 'id-karakter',
      name: 'Nama Karakter',
      iconType: 'custom',
      description: 'Tokoh roleplay beserta dunianya.',
      greeting: 'Sapaan in-character, sesuai kepribadian tokoh.',
      systemPrompt: 'Kamu berperan sebagai tokoh berikut. Jangan pernah keluar dari peran. Narasikan aksi dengan tanda *asterisk*.',
      avatarUri: '',
      temperature: 0.8,
      topK: 40,
      personality: 'Kepribadian, cara bicara, kebiasaan tokoh.',
      scenario: 'Latar tempat dan situasi awal roleplay.',
      exampleDialogues: 'User: *melambaikan tangan*\nKarakter: *tersenyum* Hai! ...',
      author: '',
      lang: 'id',
      tags: ['roleplay'],
    },
  },
];

function initTemplates() {
  const grid = document.getElementById('templates-grid');
  if (!grid) return;

  TEMPLATES.forEach(t => {
    const card = document.createElement('div');
    card.className = 'template-card';

    const title = document.createElement('h3');
    title.textContent = t.title;

    const desc = document.createElement('p');
    desc.className = 'template-desc';
    desc.textContent = t.desc;

    const fields = document.createElement('div');
    fields.className = 'template-fields';
    const filled = Object.entries(t.data).filter(([, v]) => {
      if (v === null) return false;
      if (Array.isArray(v)) return v.length > 0;
      return String(v).trim() !== '';
    }).length;
    const total = Object.keys(t.data).length;
    const badge = document.createElement('span');
    badge.className = 'tag';
    badge.textContent = `${filled}/${total} field terisi`;
    fields.appendChild(badge);
    const schemaTag = document.createElement('span');
    schemaTag.className = 'tag';
    schemaTag.textContent = 'schema v1';
    fields.appendChild(schemaTag);

    const actions = document.createElement('div');
    actions.className = 'template-actions';

    const copyBtn = document.createElement('button');
    copyBtn.type = 'button';
    copyBtn.className = 'btn btn-outline';
    copyBtn.innerHTML = '<span>Salin</span>';
    copyBtn.addEventListener('click', () => {
      copyText(JSON.stringify(t.data, null, 2), `Template ${t.title} tersalin.`);
    });

    const dlBtn = document.createElement('button');
    dlBtn.type = 'button';
    dlBtn.className = 'btn btn-primary';
    dlBtn.innerHTML = '<span>Unduh</span>';
    dlBtn.addEventListener('click', () => {
      const blob = new Blob([JSON.stringify(t.data, null, 2)], { type: 'application/json' });
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = `${t.id}.json`;
      document.body.appendChild(a);
      a.click();
      document.body.removeChild(a);
      URL.revokeObjectURL(url);
      showToast(`Template ${t.title} diunduh.`);
    });

    actions.appendChild(copyBtn);
    actions.appendChild(dlBtn);

    card.appendChild(title);
    card.appendChild(desc);
    card.appendChild(fields);
    card.appendChild(actions);
    grid.appendChild(card);
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
