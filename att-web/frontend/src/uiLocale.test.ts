import { afterEach, describe, expect, it } from 'vitest';
import { applyUiLocale, detectUiLocale, readUiLocale, readUiLocalePreference, saveUiLocalePreference } from './uiLocale';

describe('Studio language preference', () => {
  afterEach(() => {
    saveUiLocalePreference('browser');
    applyUiLocale('en');
    document.body.replaceChildren();
  });

  it('detects Traditional Chinese browsers and keeps Simplified Chinese on English', () => {
    expect(detectUiLocale(['zh-HK', 'en-US'])).toBe('zh-Hant');
    expect(detectUiLocale(['zh-CN', 'en-US'])).toBe('en');
    expect(detectUiLocale(['en-US'])).toBe('en');
  });

  it('stores explicit choices and restores browser default when cleared', () => {
    expect(readUiLocalePreference()).toBe('browser');
    saveUiLocalePreference('zh-Hant');
    expect(readUiLocalePreference()).toBe('zh-Hant');
    expect(readUiLocale()).toBe('zh-Hant');
    saveUiLocalePreference('browser');
    expect(readUiLocalePreference()).toBe('browser');
    expect(localStorage.getItem('att-ui-language')).toBeNull();
  });

  it('translates rendered text and accessibility labels while leaving source code intact', () => {
    const section = document.createElement('section');
    section.innerHTML = '<button aria-label="Search pages">Dashboard</button><pre>Dashboard</pre>';
    document.body.append(section);

    applyUiLocale('zh-Hant');

    expect(section.querySelector('button')?.textContent).toBe('儀表板');
    expect(section.querySelector('button')?.getAttribute('aria-label')).toBe('搜尋頁面');
    expect(section.querySelector('pre')?.textContent).toBe('Dashboard');
    expect(document.documentElement.lang).toBe('zh-Hant');

    applyUiLocale('en');
    expect(section.querySelector('button')?.textContent).toBe('Dashboard');
    expect(section.querySelector('button')?.getAttribute('aria-label')).toBe('Search pages');
  });
});
