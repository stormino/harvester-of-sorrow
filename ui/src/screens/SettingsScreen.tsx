import s from './Screen.module.css';

export function SettingsScreen() {
  return (
    <div id="settings-view" className={`${s.screen} ${s.narrow}`}>
      <h2 className={s.title}>Settings</h2>
    </div>
  );
}
