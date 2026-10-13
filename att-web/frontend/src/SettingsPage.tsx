import { useAppShellContext, type ThemePreference } from './AppShell';
import './SettingsPage.css';

const themeOptions: Array<{ value: ThemePreference; label: string; description: string }> = [
  { value: 'system', label: 'System', description: 'Follow the appearance preference set by your operating system.' },
  { value: 'light', label: 'Light', description: 'Use the light color theme on this browser.' },
  { value: 'dark', label: 'Dark', description: 'Use the dark color theme on this browser.' },
];

export function SettingsPage() {
  const { themePreference, onThemePreferenceChange } = useAppShellContext();

  return (
    <section className="page-content att-settings-page" aria-labelledby="settings-title">
      <header className="page-heading">
        <div>
          <p className="eyebrow">ATT Studio</p>
          <h1 id="settings-title">Settings</h1>
          <p className="page-description">Manage preferences for this browser. These choices do not change server configuration.</p>
        </div>
      </header>

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
        <p className="att-settings-storage-note">The selected preference is stored in this browser. Selecting System removes the saved override.</p>
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
