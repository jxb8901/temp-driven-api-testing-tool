import { useEffect, useState } from 'react';
import { useAppShellContext, type ThemePreference } from './AppShell';
import { api, type ServerJobStorageMetrics } from './api';
import { ApiError } from './types';
import { authUiText } from './authUiText';
import { UserManagementPanel } from './UserManagement';
import './SettingsPage.css';

const themeOptions: Array<{ value: ThemePreference; label: string; description: string }> = [
  { value: 'system', label: 'System', description: 'Follow the appearance preference set by your operating system.' },
  { value: 'light', label: 'Light', description: 'Use the light color theme on this browser.' },
  { value: 'dark', label: 'Dark', description: 'Use the dark color theme on this browser.' },
];

export function SettingsPage() {
  const { themePreference, onThemePreferenceChange, uiLocale, uiLocalePreference, onUiLocaleChange, authMode, accessCapabilities, preferenceStatus } = useAppShellContext();
  const locale = uiLocale;
  const zh=locale==='zh-Hant';
  const preferenceMessage=preferenceStatus==='saving'?(zh?'正在將偏好儲存至您的帳戶…':'Saving preferences to your account…'):preferenceStatus==='saved'?(zh?'偏好已儲存至您的帳戶。':'Preferences saved to your account.'):preferenceStatus==='failed'?(zh?'無法將偏好儲存至帳戶；所選值仍在本機生效。請檢查 Server 連線，然後再次變更以重試。':'Could not save preferences to your account. The current value remains active locally; check the Server connection and change it again to retry.'):preferenceStatus==='browser-only'?(zh?'帳戶偏好儲存區不可用；偏好只會儲存在此瀏覽器。':'Account preference storage is unavailable. Preferences use this browser only.'):preferenceStatus==='idle'?(zh?'尚未儲存帳戶偏好；目前使用瀏覽器及系統預設值。':'No account preference is saved yet; browser and system defaults are active.'):(zh?'正在載入帳戶偏好…':'Loading account preferences…');

  return (
    <section className="page-content att-settings-page" aria-labelledby="settings-title">
      <header className="page-heading">
        <div>
          <p className="eyebrow">ATT Studio</p>
          <h1 id="settings-title">{authUiText('settingsTitle',locale)}</h1>
          <p className="page-description">{authUiText('settingsDescription',locale)}</p>
        </div>
      </header>

      {(accessCapabilities.accountManage || authMode === 'container') && <UserManagementPanel />}
      {accessCapabilities.systemSettingsWrite && <ServerStoragePanel />}
      <p className="att-settings-persistence" role="status" aria-live="polite">{preferenceMessage}</p>

      <section className="panel att-settings-section" aria-labelledby="appearance-title">
        <div className="att-settings-section-heading">
          <div>
            <h2 id="appearance-title">Appearance</h2>
            <p className="att-settings-help">Choose how ATT Studio displays colors and contrast.</p>
          </div>
          <span className="att-settings-current" role="status" aria-live="polite">Theme preference: {themePreference === 'system' ? 'System' : themePreference === 'dark' ? 'Dark' : 'Light'}.</span>
        </div>
        <fieldset className="att-settings-options">
          <legend className="sr-only">Color theme preference</legend>
          {themeOptions.map(option => (
            <label className="att-settings-option" key={option.value}>
              <input
                type="radio"
                name="theme-preference"
                value={option.value}
                checked={themePreference === option.value}
                onChange={() => onThemePreferenceChange(option.value)}
              />
              <span className="att-settings-option-copy">
                <strong>{option.label}</strong>
                <small>{option.description}</small>
              </span>
            </label>
          ))}
        </fieldset>
        <p className="att-settings-storage-note">{zh?'「系統」會跟隨作業系統；伺服器支援時，會儲存為帳戶偏好。':'System follows the operating system and is saved as an account preference when the Server supports it.'}</p>
      </section>

      <section className="panel att-settings-section" aria-labelledby="language-title">
        <div className="att-settings-section-heading">
          <div>
            <h2 id="language-title">Language</h2>
            <p className="att-settings-help">Choose the language used throughout ATT Studio.</p>
          </div>
          <span className="att-settings-current" role="status" aria-live="polite">
            {locale === 'zh-Hant' ? '目前語言：繁體中文。' : 'Current language: English.'}
          </span>
        </div>
        <fieldset className="att-settings-options">
          <legend className="sr-only">Studio language preference</legend>
          {([
            { value: 'browser' as const, label: 'Browser default', description: 'Follow this browser’s language unless you choose a language below.' },
            { value: 'en' as const, label: 'English', description: 'Use English throughout ATT Studio.' },
            { value: 'zh-Hant' as const, label: 'Traditional Chinese', description: 'Use Traditional Chinese throughout ATT Studio.' },
          ]).map(option => (
            <label className="att-settings-option" key={option.value}>
              <input
                type="radio"
                name="ui-language"
                value={option.value}
                checked={uiLocalePreference === option.value}
                onChange={() => onUiLocaleChange(option.value)}
              />
              <span className="att-settings-option-copy">
                <strong>{option.label}</strong>
                <small>{option.description}</small>
              </span>
            </label>
          ))}
        </fieldset>
        <p className="att-settings-storage-note">{zh?'「瀏覽器預設」會跟隨此瀏覽器；伺服器支援時，會儲存為帳戶偏好。':'Browser default follows this browser and is saved as an account preference when the Server supports it.'}</p>
      </section>

      <section className="panel att-settings-section" aria-labelledby="keyboard-settings-title">
        <div className="att-settings-section-heading">
          <div>
            <h2 id="keyboard-settings-title">Keyboard shortcuts</h2>
            <p className="att-settings-help">Use these shortcuts from any page. Text-entry fields keep their normal typing behavior.</p>
          </div>
        </div>
        <dl className="att-settings-shortcuts">
          <div><dt>Search and open a page</dt><dd><kbd>Ctrl K</kbd> or <kbd>⌘ K</kbd>, or <kbd>/</kbd></dd></div>
          <div><dt>Show shortcut help</dt><dd><kbd>?</kbd></dd></div>
          <div><dt>Close a dialog or mobile navigation</dt><dd><kbd>Escape</kbd></dd></div>
          <div><dt>Start a validated Load draft</dt><dd><kbd>Ctrl Enter</kbd> or <kbd>⌘ Enter</kbd>; confirmation is required</dd></div>
        </dl>
      </section>
    </section>
  );
}

function ServerStoragePanel() {
  const [report, setReport] = useState<ServerJobStorageMetrics | null>(null);
  const [message, setMessage] = useState('');
  useEffect(() => {
    const controller = new AbortController();
    api.getServerMetrics(controller.signal).then(value => {
      if (!controller.signal.aborted) setReport(value.jobStorage || null);
    }).catch(error => {
      if (controller.signal.aborted) return;
      setMessage(error instanceof ApiError && (error.status === 401 || error.status === 403)
        ? 'Server storage metrics require system administration.'
        : 'Server storage metrics are unavailable.');
    });
    return () => controller.abort();
  }, []);
  const measured = typeof report?.totalBytes === 'number';
  return <section className="panel att-settings-section" aria-labelledby="server-storage-title">
    <div className="att-settings-section-heading">
      <div>
        <h2 id="server-storage-title">Server storage</h2>
        <p className="att-settings-help">Job directory usage under server.dataDir/jobs. This is not the H2 database file, and retention does not compact H2.</p>
      </div>
    </div>
    {message && <p role="status">{message}</p>}
    {report && <dl className="att-job-overview">
      <div><dt>Size limits</dt><dd>{report.enabled ? 'Enabled' : 'Disabled'}</dd></div>
      <div><dt>State</dt><dd>{report.state || 'Not reported'}</dd></div>
      <div><dt>Job directories</dt><dd>{report.enabled ? (measured ? formatBytes(report.totalBytes) : 'Not measured') : 'Not measured while size limits are disabled'}</dd></div>
      <div><dt>High watermark</dt><dd>{report.enabled ? formatBytes(report.maxTotalBytes) : 'Not configured'}</dd></div>
      <div><dt>Cleanup target</dt><dd>{report.enabled ? formatBytes(report.cleanupTargetBytes) : 'Not configured'}</dd></div>
      <div><dt>Minimum retention</dt><dd>{report.minRetentionHours ?? 'Not reported'} hours</dd></div>
      <div><dt>Last sweep</dt><dd>{report.lastSweepAt || 'No sweep recorded'}</dd></div>
      <div><dt>Last sweep deletions</dt><dd>{report.deletedJobs ?? 'Not reported'}</dd></div>
      <div><dt>Last sweep reclaimed</dt><dd>{typeof report.reclaimedBytes === 'number' ? formatBytes(report.reclaimedBytes) : 'Not reported'}</dd></div>
    </dl>}
  </section>;
}

function formatBytes(value?: number | null): string {
  if (typeof value !== 'number' || !Number.isFinite(value) || value < 0) return 'Not measured';
  if (value < 1024) return `${value} B`;
  const units = ['KiB', 'MiB', 'GiB', 'TiB'];
  let amount = value / 1024;
  let unit = 0;
  while (amount >= 1024 && unit < units.length - 1) { amount /= 1024; unit += 1; }
  return `${new Intl.NumberFormat(undefined, { maximumFractionDigits: 1 }).format(amount)} ${units[unit]}`;
}
