(() => {
  'use strict';

  const body = document.body;
  const sidebarToggle = document.getElementById('sidebar-toggle');
  const sidebarCollapse = document.getElementById('sidebar-collapse');
  const backdrop = document.getElementById('sidebar-backdrop');
  const navigation = document.getElementById('primary-navigation');
  const themeToggle = document.getElementById('theme-toggle');
  const mobileLayout = window.matchMedia('(max-width: 820px)');
  const compactLayout = window.matchMedia('(max-width: 1100px)');

  function readPreference(key) {
    try { return window.localStorage.getItem(key); }
    catch (_) { return null; }
  }

  function writePreference(key, value) {
    try { window.localStorage.setItem(key, value); }
    catch (_) { /* The shell remains usable when browser storage is disabled. */ }
  }

  function setSidebarExpanded(expanded, persist = true) {
    body.classList.toggle('sidebar-expanded', expanded);
    body.classList.toggle('sidebar-collapsed', !expanded);
    sidebarCollapse.setAttribute('aria-expanded', String(expanded));
    sidebarCollapse.setAttribute('aria-label', expanded ? 'Collapse navigation' : 'Expand navigation');
    sidebarCollapse.title = expanded ? 'Collapse navigation' : 'Expand navigation';
    sidebarCollapse.querySelector('span').textContent = expanded ? '‹' : '›';
    if (persist) writePreference('att-ui-sidebar', expanded ? 'expanded' : 'collapsed');
  }

  function setMobileNavigation(open) {
    body.classList.toggle('sidebar-open', open);
    sidebarToggle.setAttribute('aria-expanded', String(open));
    sidebarToggle.setAttribute('aria-label', open ? 'Close navigation' : 'Open navigation');
    backdrop.tabIndex = open ? 0 : -1;
    if (open) {
      const current = navigation.querySelector('[aria-current="page"]');
      if (current) current.focus({ preventScroll: true });
    }
  }

  const savedSidebar = readPreference('att-ui-sidebar');
  const initiallyExpanded = savedSidebar === 'expanded' || (savedSidebar !== 'collapsed' && !compactLayout.matches);
  setSidebarExpanded(initiallyExpanded, false);

  sidebarToggle.addEventListener('click', () => {
    if (mobileLayout.matches) setMobileNavigation(!body.classList.contains('sidebar-open'));
    else setSidebarExpanded(!body.classList.contains('sidebar-expanded'));
  });
  sidebarCollapse.addEventListener('click', () => setSidebarExpanded(!body.classList.contains('sidebar-expanded')));
  backdrop.addEventListener('click', () => setMobileNavigation(false));
  navigation.addEventListener('click', event => {
    if (mobileLayout.matches && event.target.closest('a')) setMobileNavigation(false);
  });
  document.addEventListener('keydown', event => {
    if (event.key === 'Escape' && body.classList.contains('sidebar-open')) {
      setMobileNavigation(false);
      sidebarToggle.focus();
    }
  });
  mobileLayout.addEventListener('change', event => {
    if (!event.matches) setMobileNavigation(false);
    if (!readPreference('att-ui-sidebar')) setSidebarExpanded(!compactLayout.matches, false);
  });
  compactLayout.addEventListener('change', event => {
    if (!mobileLayout.matches && !readPreference('att-ui-sidebar')) setSidebarExpanded(!event.matches, false);
  });

  function effectiveTheme() {
    return document.documentElement.dataset.theme || (window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light');
  }

  function updateThemeButton() {
    const dark = effectiveTheme() === 'dark';
    themeToggle.setAttribute('aria-pressed', String(dark));
    themeToggle.setAttribute('aria-label', dark ? 'Switch to light theme' : 'Switch to dark theme');
    themeToggle.querySelector('.theme-icon').textContent = dark ? '☾' : '☀';
    themeToggle.querySelector('.theme-label').textContent = dark ? 'Dark' : 'Light';
  }

  const savedTheme = readPreference('att-ui-theme');
  if (savedTheme === 'light' || savedTheme === 'dark') document.documentElement.dataset.theme = savedTheme;
  updateThemeButton();
  themeToggle.addEventListener('click', () => {
    const nextTheme = effectiveTheme() === 'dark' ? 'light' : 'dark';
    document.documentElement.dataset.theme = nextTheme;
    writePreference('att-ui-theme', nextTheme);
    updateThemeButton();
  });
  window.matchMedia('(prefers-color-scheme: dark)').addEventListener('change', () => {
    if (!readPreference('att-ui-theme')) updateThemeButton();
  });
})();
