import { describe, expect, it } from 'vitest';
import { contextRelativeReturnTo } from './contextPaths';

describe('context-relative authentication return paths', () => {
  it('preserves route, query, and hash under a WAR context', () => {
    expect(contextRelativeReturnTo('/att/ui/index.html', '?tab=configuration', '#source', 'https://example.test/att/ui/'))
      .toBe('/ui/index.html?tab=configuration#source');
  });

  it('preserves a reverse-proxy prefix present in the browser URL', () => {
    expect(contextRelativeReturnTo('/proxy/att/ui/index.html', '?mode=debug', '#target', 'https://example.test/proxy/att/ui/'))
      .toBe('/ui/index.html?mode=debug#target');
  });

  it('falls back to the Studio root when the current URL is outside the application context', () => {
    expect(contextRelativeReturnTo('/elsewhere/ui/index.html', '', '', 'https://example.test/att/ui/')).toBe('/ui/');
  });
});
