import s from './Screen.module.css';

export function DownloadsScreen() {
  return (
    <div className={s.screen}>
      <h2 className={s.title}>Downloads</h2>
      <button id="clear-completed-button" type="button">Clear Completed</button>
      <div id="queue-grid" />
    </div>
  );
}
