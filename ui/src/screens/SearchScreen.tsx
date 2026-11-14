import s from './Screen.module.css';

export function SearchScreen() {
  return (
    <div className={s.screen}>
      <h2 className={s.title}>Search</h2>
      <input id="search-input" placeholder="Search movies and TV shows..." />
      <button id="search-button" type="button">Search</button>
      <div id="search-results" />
    </div>
  );
}
