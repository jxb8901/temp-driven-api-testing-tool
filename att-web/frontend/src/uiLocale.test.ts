import { afterEach, describe, expect, it } from 'vitest';
import { applyUiLocale, detectUiLocale, readUiLocale, readUiLocalePreference, saveUiLocalePreference, translateUiMessage } from './uiLocale';

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

  it('translates accessible Run confirmation prompts', () => {
    expect(translateUiMessage('Confirm Run', 'zh-Hant')).toBe('確認執行');
    expect(translateUiMessage('Run checkout now?', 'zh-Hant')).toBe('現在要執行 checkout 嗎？');
    expect(translateUiMessage('Run Test Case payments.C1 now?', 'zh-Hant')).toBe('現在要執行測試案例 payments.C1 嗎？');
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
    section.innerHTML = '<button aria-label="Search pages">Dashboard</button><p>Recent jobs</p><p>Recent outcomes</p>'
      + '<p>Nonterminal jobs in the returned rows</p><p>Counts from the latest 100 job rows returned by the Server</p><p>PASS</p><pre>Dashboard</pre>';
    document.body.append(section);

    applyUiLocale('zh-Hant');

    expect(section.querySelector('button')?.textContent).toBe('儀表板');
    expect(section.querySelector('button')?.getAttribute('aria-label')).toBe('搜尋頁面');
    expect([...section.querySelectorAll('p')].map(node => node.textContent)).toEqual([
      '近期工作', '近期結果', '回傳資料中的未終結工作', '伺服器回傳的最近 100 筆工作統計', '通過',
    ]);
    expect(section.querySelector('pre')?.textContent).toBe('Dashboard');
    expect(document.documentElement.lang).toBe('zh-Hant');

    applyUiLocale('en');
    expect(section.querySelector('button')?.textContent).toBe('Dashboard');
    expect(section.querySelector('button')?.getAttribute('aria-label')).toBe('Search pages');
    expect([...section.querySelectorAll('p')].map(node => node.textContent)).toEqual([
      'Recent jobs', 'Recent outcomes', 'Nonterminal jobs in the returned rows',
      'Counts from the latest 100 job rows returned by the Server', 'PASS',
    ]);
  });

  it('covers the new source authoring and Package catalog labels in Traditional Chinese', () => {
    const section = document.createElement('section');
    section.innerHTML = '<h2>Executable Package resources</h2><button>Import Package ZIP</button>'
      + '<h3>Candidate file changes</h3><label>Filter changed files<input aria-label="Related file path"></label>'
      + '<h3>New Testdata descriptor</h3><select aria-label="Testdata record format"><option>Generated sequence</option></select>'
      + '<button>Prepare atomic candidate</button><h2>Testcase workbook and snapshot</h2><button>Generate snapshot</button>'
      + '<h3>Source history</h3><button title="Stage this complete historical source snapshot as a new draft">Stage restore as draft</button>'
      + '<p>Global Runtime Configuration applies across Packages. The source inspector is read-only; authorized ADMINs author changes as a separate reviewed draft.</p>'
      + '<p>Publish the reviewed draft for packages/payments/config.yaml. This changes the Package source used across Packages.</p>'
      + '<button>Inspector off</button>';
    document.body.append(section);

    applyUiLocale('zh-Hant');

    expect(section.querySelector('h2')?.textContent).toBe('可執行的 Package 資源');
    expect(section.querySelector('button')?.textContent).toBe('匯入 Package ZIP');
    expect(section.querySelector('label')?.textContent).toBe('篩選變更的檔案');
    expect(section.querySelector('input')?.getAttribute('aria-label')).toBe('相關檔案路徑');
    expect(section.querySelector('select')?.getAttribute('aria-label')).toBe('Testdata 記錄格式');
    expect(section.querySelectorAll('button')[1]?.textContent).toBe('準備原子候選變更');
    expect(section.querySelectorAll('button')[2]?.textContent).toBe('產生快照');
    expect(section.querySelectorAll('button')[3]?.textContent).toBe('將還原內容建立為草稿');
    expect(section.querySelectorAll('button')[3]?.getAttribute('title')).toBe('將此完整歷史來源快照建立為新草稿');
    expect(section.querySelector('p')?.textContent).toBe('全域 Runtime Configuration 適用於所有 Package。來源檢視器為唯讀；具權限的 ADMIN 會另外以草稿編輯並檢閱變更。');
    expect(section.querySelectorAll('p')[1]?.textContent)
      .toBe('發布 packages/payments/config.yaml 的已檢閱草稿。此操作會變更 Package 來源（所有 Package 共用）。');
    expect(section.querySelectorAll('button')[4]?.textContent).toBe('檢視器關閉');

    applyUiLocale('en');
    expect(section.querySelector('button')?.textContent).toBe('Import Package ZIP');
    expect(section.querySelector('input')?.getAttribute('aria-label')).toBe('Related file path');
  });

  it('translates the compact publication state and advanced revision disclosures', () => {
    const section = document.createElement('section');
    section.innerHTML = '<div aria-label="Source publication status"><strong>Published and active</strong>'
      + '<details aria-label="Advanced source revision provenance"><summary>Advanced revision provenance</summary>'
      + '<dt>Package source revision</dt><dd>package-revision</dd></details></div>'
      + '<div aria-label="Testcase publication status"><strong>Testcase snapshot published</strong>'
      + '<details aria-label="Advanced Testcase revision provenance"><summary>Advanced revision provenance</summary></details></div>';
    document.body.append(section);

    applyUiLocale('zh-Hant');

    expect(section.querySelector('[aria-label="來源發布狀態"] strong')?.textContent).toBe('已發布並啟用');
    expect(section.querySelector('[aria-label="進階來源 revision provenance"] summary')?.textContent).toBe('進階 revision provenance');
    expect(section.querySelector('[aria-label="Testcase 發布狀態"] strong')?.textContent).toBe('Testcase snapshot 已發布');

    applyUiLocale('en');
    expect(section.querySelector('[aria-label="Source publication status"] strong')?.textContent).toBe('Published and active');
  });
});
