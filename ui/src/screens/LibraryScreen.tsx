import s from './Screen.module.css';

export function LibraryScreen() {
  return (
    <div className={s.screen}>
      <h2 id="library-title" className={s.title}>Library</h2>
      <button id="library-refresh-button" type="button">Refresh</button>
      <div id="library-grid" />
    </div>
  );
}
