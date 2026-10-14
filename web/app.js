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
    const visible = characters.filter(c => matchesQuery(c, filter.trim()));

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
      (c.tags || []).forEach(t => {
        const tag = document.createElement('span');
        tag.className = 'tag';
        tag.textContent = t;
        tags.appendChild(tag);
      });

      const meta = document.createElement('div');
      meta.className = 'character-meta tabular';
      meta.textContent = paramText(c);

      card.appendChild(header);
      card.appendChild(desc);
      if ((c.tags || []).length) card.appendChild(tags);
      card.appendChild(meta);

      card.style.transitionDelay = `${Math.min(index, 5) * 60}ms`;
      card.addEventListener('click', () => openModal(c));
      grid.appendChild(card);
    });

    if (searchCount) {
      searchCount.textContent = `${visible.length} dari ${characters.length} karakter`;
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

/* ==========================================================================
   CHARACTER DETAIL MODAL
   ========================================================================== */
let modalCharacter = null;

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

  document.getElementById('modal-avatar').textContent = glyphFor(c.iconType);
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
  const blob = new Blob([JSON.stringify(modalCharacter, null, 2)], { type: 'application/json' });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = `${modalCharacter.id || 'character'}.json`;
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  URL.revokeObjectURL(url);
  showToast(`${modalCharacter.name} diunduh.`);
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
      copyText(JSON.stringify(modalCharacter, null, 2), 'JSON tersalin ke clipboard.');
    });
  }

  document.addEventListener('keydown', (e) => {
    const bd = document.getElementById('modal-backdrop');
    if (e.key === 'Escape' && bd && !bd.hidden) closeModal();
  });
});

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
