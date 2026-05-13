# Mobile Redesign Plan

## Goal

Restyle the VixSrc Downloader UI to be significantly more pleasant to use on both
desktop and mobile devices. The app is built with Vaadin 24 (server-side Java) and
a custom Lumo theme override in `frontend/themes/vixsrc/styles/theme.css`.

---

## Current Pain Points

### Navigation / Layout
- The `MainLayout` uses Vaadin `AppLayout` with a collapsible drawer. On mobile the
  drawer toggle works but the drawer overlaps content and has no visual affordance to
  close it (tap-outside is easy to miss).
- The navbar only contains a title + version. No active-route indicator in the header
  on mobile (the sidenav items are hidden inside the drawer).
- No bottom navigation bar for mobile — the standard pattern for small screens.

### Search View (`SearchView.java`)
- The search bar, content-type radio group, and search button are in a single
  `HorizontalLayout` that wraps via `flex-wrap: wrap`. On narrow screens the radio
  group breaks weirdly; the label "Type" is truncated.
- Results grid uses `minmax(min(100%, 320px), 1fr)`. At 320 px a card is already at
  full width but still cramped with padding on both sides.
- No loading skeleton / spinner: the button text changes to "Searching..." but the
  grid shows nothing during the wait — users don't know if the app is working.

### Search Result Card (`SearchResultCard.java`)
- Card hover effects (`translateY(-2px)`) do not fire on touch devices — they rely on
  `mouseenter`/`mouseleave` DOM events, which don't exist on mobile.
- Download button is small (`LUMO_SMALL`). On mobile it's a difficult tap target.
- Clicking the card opens the TMDB page in a new tab. The entire card is a link, but
  a download button inside it stops propagation. The distinction between "click card →
  open TMDB" and "click button → download dialog" is unclear without hover state.

### Download Dialog (`SearchResultCard.buildDownloadDialog`)
- Fixed `width: 500px` — wider than most phone screens. On small screens this causes
  horizontal overflow or gets squashed.
- Fields are stacked vertically but without spacing tokens, so they crowd together.
- The dialog footer has "Add to Queue" + "Cancel" side by side. On mobile they can
  be tiny.

### Download Queue View (`DownloadQueueView.java`)
- Uses a `TreeGrid` with 8 columns. Even on desktop this is tight; on mobile, the CSS
  tries to hide some columns by targeting generated slot names like
  `vaadin-grid-cell-content[slot^="vaadin-grid-column-4"]`. This is fragile —
  column indices shift if columns are reordered.
- The grid has a hardcoded `height: 600px`. On small screens this either clips or
  overflows.
- Action buttons (cancel, retry, info) are tiny icon-only buttons inside a grid cell —
  hard to tap precisely.
- Status bar (counts, speed, disk space) is three separate `Span` elements in a
  `VerticalLayout`. On mobile they stack and consume too much vertical real estate.

### CSS (`theme.css`)
- Font sizes are already reduced by 20–25 %. On mobile `--lumo-font-size-m: 0.8125rem`
  (~13 px) is borderline too small for comfortable reading.
- The responsive media queries hide columns by fragile slot selectors and don't adjust
  the grid height or action button sizes.
- No dark-mode support.
- Button hover `translateY(-1px)` is desktop-only; no active/pressed state designed
  for touch.

### Settings View (`SettingsView.java`)
- All fields are read-only and displayed in a long vertical scroll. No visual grouping
  with card containers. On mobile, this is a long, boring wall of text.

---

## Proposed Changes

### Phase 1 — CSS & Theme Fixes (lowest risk, highest impact)

**File:** `frontend/themes/vixsrc/styles/theme.css`

1. **Restore mobile font sizes** — restore `--lumo-font-size-m` to at least `0.875rem`
   on screens ≤ 480 px using a media query so readability improves.
2. **Touch-friendly active state** — replace or supplement `vaadin-button:hover`
   `translateY` with a `:active` press effect that works on touch:
   ```css
   vaadin-button:active {
       transform: scale(0.97);
       box-shadow: none;
   }
   ```
3. **Fix dialog width** — add a CSS rule so dialogs never exceed the viewport width:
   ```css
   vaadin-dialog-overlay::part(overlay) {
       max-width: min(500px, 95vw);
       width: min(500px, 95vw);
   }
   ```
4. **Increase minimum tap target** — ensure buttons in grid cells meet 44 px height:
   ```css
   vaadin-button[theme~="small"] {
       min-height: 44px;
       min-width: 44px;
   }
   ```
5. **Replace fragile column-hiding** — remove slot-name selectors and instead give
   each TreeGrid column a stable CSS class from Java, then hide via class selectors.
6. **Improve card styles** — add an `:active` state equivalent to hover for touch:
   ```css
   .result-card:active {
       transform: translateY(-2px);
       box-shadow: 0 8px 16px rgba(33,150,243,0.25);
   }
   ```
7. **Add dark-mode awareness** — wrap color palette in
   `@media (prefers-color-scheme: dark)` with inverted surface colours.

---

### Phase 2 — Layout & Navigation

**File:** `MainLayout.java`

1. **Bottom navigation bar for mobile** — add a second `HorizontalLayout` with three
   icon+label nav items (Search, Downloads, Settings) positioned at the bottom of the
   viewport via CSS (`position: fixed; bottom: 0`). Show it only on `≤ 768px` using a
   CSS class toggled on the element.
   - Alternatively, configure `AppLayout` with `setPrimarySection(Section.DRAWER)` and
     add `setDrawerOpened(false)` on narrow viewports via `Page.addBrowserWindowResizeListener`.
2. **Active route highlight in header** — add the current page title as a `Span` in the
   header so mobile users always know where they are without opening the drawer.

---

### Phase 3 — Search View

**File:** `SearchView.java`

1. **Vertical search bar layout on mobile** — change the `HorizontalLayout` to a
   `VerticalLayout` on narrow widths, or use `FlexLayout` with a `flex-direction` CSS
   override for mobile. At minimum, move the radio group above the text field so the
   field + button remain side by side.
2. **Loading state with spinner** — add a `Div` spinner element (via `lumo-icon` or a
   simple CSS animation) that is shown while search is in progress, rather than only
   changing button text.
3. **Empty state illustration** — replace the blank area before search with a short
   prompt ("Search for a movie or TV show to get started") to orient first-time users.

---

### Phase 4 — Download Queue View

**File:** `DownloadQueueView.java`

1. **Fluid grid height** — replace `treeGrid.setHeight("600px")` with
   `treeGrid.setSizeFull()` and let the parent layout control available height via
   `setSizeFull()` + `setFlexGrow(1, treeGrid)`.
2. **Mobile-first column strategy** — assign named CSS classes to each column from
   Java (`.col-title`, `.col-status`, `.col-progress`, `.col-size`, `.col-speed`,
   `.col-eta`, `.col-created`, `.col-actions`), then hide non-essential ones in CSS:
   - **≤ 768 px**: hide `.col-size`, `.col-eta`, `.col-created`
   - **≤ 480 px**: also hide `.col-speed`
3. **Swipe-to-cancel** — for mobile, consider a long-press or swipe gesture on a row
   to reveal action buttons, instead of always-visible icon buttons (lower priority).
4. **Status bar consolidation** — merge the three status `Span` elements into a single
   line with a bullet separator on desktop; stack them only on mobile.
5. **Larger action buttons** — switch cancel/retry/info buttons from `LUMO_SMALL` to
   default size on `≤ 480px` using a CSS class on the grid.

---

### Phase 5 — Search Result Card

**File:** `SearchResultCard.java`

1. **Replace DOM mouseenter/mouseleave with CSS** — move hover effects to pure CSS
   (already partially done in `theme.css`); remove the JS event listeners from Java.
   Add a CSS class (e.g., `result-card`) and rely on `:hover` + `:active` only.
2. **Larger download button** — remove `LUMO_SMALL` on the download button so it is
   at least 44 px tall.
3. **Clearer CTA area** — add a thin separator or background contrast band at the
   bottom of the card to visually separate the "Download" button from the metadata.

---

### Phase 6 — Download Dialog

**File:** `SearchResultCard.java` (`buildDownloadDialog`)

1. **Remove fixed width** — replace `downloadDialog.setWidth("500px")` with no width
   setting; let CSS rule from Phase 1 handle `max-width: min(500px, 95vw)`.
2. **Full-width footer buttons** — stack "Add to Queue" and "Cancel" vertically on
   mobile, each full width.
3. **Better season/episode UX** — add inline helper text explaining "leave blank for
   all" more prominently; consider a segmented selector (All / Specific) to avoid
   users needing to know to leave a field empty.

---

### Phase 7 — Settings View

**File:** `SettingsView.java`

1. **Card grouping** — wrap each section (TMDB, Download, Extractor, System) in a
   `vaadin-vertical-layout[theme~="card"]` container for visual separation.
2. **Collapsible sections** — for mobile, consider wrapping each section in a
   `vaadin-details` (Vaadin accordion) so users can expand only what they need.
3. **Info density** — in the System Information section, switch from `Paragraph` to
   a two-column key/value `FormLayout` to use horizontal space better on desktop.

---

## File Change Summary

| File | Changes |
|------|---------|
| `frontend/themes/vixsrc/styles/theme.css` | Font size fix, touch states, dialog max-width, tap targets, dark mode, stable column classes |
| `MainLayout.java` | Bottom nav bar for mobile, active route in header |
| `SearchView.java` | Vertical search layout on mobile, loading spinner, empty state |
| `DownloadQueueView.java` | Fluid grid height, named column classes, status bar consolidation, larger action buttons |
| `SearchResultCard.java` | CSS-only hover/active effects, larger download button, remove fixed dialog width |
| `SettingsView.java` | Card grouping, collapsible sections |

---

## Implementation Order

1. **Phase 1 (CSS)** — immediately visible improvement on all screens, zero Java risk.
2. **Phase 4 (Queue grid)** — highest UX pain on mobile; grid height and column hiding.
3. **Phase 3 (Search layout)** — search is the primary entry point.
4. **Phase 5 & 6 (Cards & Dialog)** — polishes the download flow.
5. **Phase 2 (Navigation)** — bottom nav bar requires careful layout coordination.
6. **Phase 7 (Settings)** — lowest traffic view; do last.

---

## Out of Scope

- Changing the backend API or download logic.
- Adding new features beyond what already exists.
- PWA offline support (service worker already in Vite config but not designed here).
- Changing the Vaadin version.
