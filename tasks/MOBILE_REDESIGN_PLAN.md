# Mobile Redesign Plan — Vaadin Aura Theme

## Theme Background

Vaadin ships two built-in themes:
- **Lumo** — original theme (24.x default), Roboto/system font, `--lumo-*` tokens
- **Aura** — new theme introduced in **Vaadin 25**, Instrument Sans font, `--aura-*` tokens,
  `oklch()` color space, CSS cascade layers

**This project is currently on Vaadin 24.3.2.** Aura is not available as a built-in theme
at this version. The implementation plan therefore describes two tracks:

| Track | What |
|-------|------|
| **A — Upgrade to Vaadin 25** | Migrate to Vaadin 25 and use `@Theme(Aura.class)` natively |
| **B — Aura-style Lumo override (24.3)** | Stay on 24.3, override `--lumo-*` tokens to match Aura's visual language |

Track B is the lower-risk, lower-effort path and is recommended unless there is a
separate reason to upgrade. The HTML prototype (`mobile-redesign-preview.html`) already
shows the target visual result — it is faithful to the actual Aura token values obtained
from the `packages/aura/src/` source files in `vaadin/web-components`.

---

## Aura Design Tokens Reference

The values below come directly from the Aura source CSS
(`packages/aura/src/{palette,color,typography,size,surface}.css`):

### Typography
| Token | Value |
|-------|-------|
| `--aura-font-family` | `'Instrument Sans', system-ui, ui-sans-serif, sans-serif` |
| `--aura-base-font-size` | `14` (px, unitless) |
| `--aura-font-size-m` | `round(14/16 * 1rem)` → **0.875 rem** |
| `--aura-font-size-s` | **0.8125 rem** |
| `--aura-font-size-xs` | **0.75 rem** |
| `--aura-font-size-l` | **0.9375 rem** |
| `--aura-font-size-xl` | **1.0625 rem** |
| `--aura-font-weight-regular` | `400` |
| `--aura-font-weight-medium` | `500` |
| `--aura-font-weight-semibold` | `600` |
| `--aura-base-line-height` | `1.4` |

### Colors (oklch)
| Token | Value |
|-------|-------|
| `--aura-blue` | `oklch(0.55 0.20 264)` |
| `--aura-green` | `oklch(0.60 0.20 155)` |
| `--aura-red` | `oklch(0.59 0.20 25)` |
| `--aura-orange` | `oklch(0.61 0.35 87)` |
| `--aura-yellow` | `oklch(0.89 0.30 98)` |
| `--aura-purple` | `oklch(0.58 0.22 290)` |
| Background (light) | `oklch(0.95 0.005 248)` |
| Background (dark) | `oklch(0.20 0.010 260)` |
| Default accent | blue (`--aura-blue`) |

### Spacing & Sizing
| Token | Value |
|-------|-------|
| `--vaadin-gap-xs` | `4px` |
| `--vaadin-gap-s` | `8px` |
| `--vaadin-gap-m` | `12px` |
| `--vaadin-gap-l` | `16px` |
| `--vaadin-gap-xl` | `24px` |
| `--vaadin-padding-s` | `8px` |
| `--vaadin-padding-m` | `12px` |
| `--vaadin-padding-l` | `16px` |
| `--vaadin-padding-xl` | `24px` |

### Border Radius
| Token | Value |
|-------|-------|
| `--vaadin-radius-s` | `5px` |
| `--vaadin-radius-m` | `9px` |
| `--vaadin-radius-l` | `15px` |

### Icon
| Token | Value |
|-------|-------|
| `--vaadin-icon-stroke-width` | `1.75` |

---

## Track A — Upgrade to Vaadin 25

### Scope
Update `pom.xml`, switch `@Theme` annotation, migrate any Lumo-specific CSS.

### Steps

1. **Bump Vaadin BOM** in `pom.xml`:
   ```xml
   <vaadin.version>25.0.1</vaadin.version>
   ```

2. **Add Aura theme dependency** (pulled transitively via BOM, but explicit is safer):
   ```xml
   <dependency>
     <groupId>com.vaadin</groupId>
     <artifactId>vaadin-aura-theme</artifactId>
   </dependency>
   ```

3. **Switch theme annotation** — find any class annotated `@Theme("vixsrc")` and
   change to `@Theme(Aura.class)` (or keep the custom theme extending Aura):
   ```java
   import com.vaadin.flow.theme.aura.Aura;
   @Theme(Aura.class)
   public class Application implements AppShellConfigurator { … }
   ```
   Alternatively, in `theme.json` set `"parent": "aura"`.

4. **Update `frontend/themes/vixsrc/styles/theme.css`** — remove Lumo overrides,
   keep only app-specific additions on top of Aura.

5. **Replace `--lumo-*` custom property references** in Java views / CSS with
   Aura equivalents (see mapping table in Track B).

6. **Test E2E suite** — run Playwright tests against the upgraded app.

### Risk
Medium — Vaadin 25 changed the theming architecture (CSS cascade layers, base
styles model). Component slot names and shadow-part selectors may have changed.

---

## Track B — Aura-style Lumo Override (Recommended for 24.3)

Override the Lumo CSS custom properties in
`frontend/themes/vixsrc/styles/theme.css` to match Aura's visual values.
No version upgrade required.

### Token Mapping (Lumo → Aura equivalent)

| Lumo token | Aura target value |
|-----------|-------------------|
| `--lumo-font-family` | `'Instrument Sans', system-ui, ui-sans-serif, sans-serif` |
| `--lumo-font-size-m` | `0.875rem` (Aura base 14 px) |
| `--lumo-font-size-s` | `0.8125rem` |
| `--lumo-font-size-xs` | `0.75rem` |
| `--lumo-font-size-l` | `0.9375rem` |
| `--lumo-font-size-xl` | `1.0625rem` |
| `--lumo-primary-color` | `oklch(0.55 0.20 264)` (Aura blue) |
| `--lumo-success-color` | `oklch(0.60 0.20 155)` (Aura green) |
| `--lumo-error-color` | `oklch(0.59 0.20 25)` (Aura red) |
| `--lumo-warning-color` | `oklch(0.89 0.30 98)` (Aura yellow) |
| `--lumo-base-color` | `oklch(0.98 0.003 248)` (Aura surface-1 light) |
| `--lumo-border-radius-s` | `5px` |
| `--lumo-border-radius-m` | `9px` |
| `--lumo-border-radius-l` | `15px` |
| `--lumo-space-xs` | `4px` |
| `--lumo-space-s` | `8px` |
| `--lumo-space-m` | `12px` |
| `--lumo-space-l` | `16px` |
| `--lumo-space-xl` | `24px` |
| `--lumo-size-m` | `36px` (Aura button/field height) |

**Add Instrument Sans** via Google Fonts in `frontend/index.html`:
```html
<link rel="preconnect" href="https://fonts.googleapis.com">
<link href="https://fonts.googleapis.com/css2?family=Instrument+Sans:wdth,wght@75..100,400..700&display=swap" rel="stylesheet">
```
Or self-host the font files in `frontend/themes/vixsrc/fonts/` and add
`@font-face` rules at the top of `theme.css`.

---

## UI Changes — View by View

### Phase 1 — `theme.css` (Aura token override)

1. Replace all `--lumo-*` overrides with the mapping table above.
2. Remove the current font-size reductions (Aura's 14 px base is already compact).
3. Add dark-mode support via `@media (prefers-color-scheme: dark)` using Aura's
   dark background `oklch(0.20 0.010 260)`.
4. Fix dialog max-width:
   ```css
   vaadin-dialog-overlay::part(overlay) {
     max-width: min(460px, 95vw);
     width: min(460px, 95vw);
   }
   ```
5. Increase minimum tap target for small buttons (Aura guideline: 36 px+ height):
   ```css
   vaadin-button[theme~="small"] { min-height: 36px; min-width: 36px; }
   @media (max-width: 480px) {
     vaadin-button[theme~="small"] { min-height: 44px; min-width: 44px; }
   }
   ```
6. Replace fragile column-hiding selectors with stable named class selectors.
7. Replace `mouseenter`/`mouseleave` hover with pure CSS `:hover` + `:active`.
8. Touch active state:
   ```css
   vaadin-button:active { transform: scale(.98); }
   ```

### Phase 2 — `MainLayout.java` (Navigation)

1. **Bottom navigation for mobile** — add a secondary `HorizontalLayout` fixed at
   the bottom of the viewport on `≤ 768px`. Each item: icon + label, using
   `SideNavItem` route info for active state.
   ```java
   Div bottomNav = new Div();
   bottomNav.addClassName("bottom-nav");
   // … add 3 icon+label buttons navigating to Search, Downloads, Settings
   addToNavbar(false, bottomNav); // or add to main layout as a separate element
   ```
   Use CSS to show/hide:
   ```css
   .bottom-nav { display: none; }
   @media (max-width: 768px) { .bottom-nav { display: flex; } .drawer { display: none; } }
   ```
2. **Current page label in navbar** — add a `Span` that shows the active page name,
   updated on `BeforeEnterEvent` via a `RouterLayout` listener.

### Phase 3 — `SearchView.java`

1. **Vertical layout on mobile** — wrap search field + filter group in a
   `FlexLayout` and set `flex-direction: column` via CSS on `≤ 768px`.
2. **Loading spinner** — add a `Div` with CSS animation shown while the async
   search futures are pending, hidden on completion.
3. **Empty-state** — add an initial state with an icon and call-to-action text
   shown before any search is performed.
4. **Results grid** — switch from `LumoUtility.Display.GRID` class to an explicit
   CSS grid:
   ```css
   grid-template-columns: repeat(auto-fill, minmax(min(100%, 272px), 1fr));
   gap: var(--vaadin-gap-m);          /* Aura: 12px */
   ```

### Phase 4 — `DownloadQueueView.java`

1. **Fluid height** — replace `treeGrid.setHeight("600px")` with:
   ```java
   treeGrid.setSizeFull();
   setFlexGrow(1, treeGrid);
   ```
2. **Named column classes** — add CSS class names to each column:
   ```java
   treeGrid.addComponentHierarchyColumn(this::createTitleCell)
       .setHeader("Title").setFlexGrow(3).setClassNameGenerator(i -> "col-title");
   // repeat for each column: col-status, col-progress, col-size,
   //                         col-speed, col-eta, col-created, col-actions
   ```
   Then in `theme.css`:
   ```css
   @media (max-width: 768px) {
     .col-size, .col-eta, .col-created { display: none; }
   }
   @media (max-width: 480px) {
     .col-speed { display: none; }
   }
   ```
3. **Status bar** — merge the three `Span` elements into one `HorizontalLayout`
   with a `·` separator, collapsing to two lines only on `≤ 480px`.
4. **Action buttons** — remove `LUMO_SMALL` on mobile (see Phase 1 CSS rule).

### Phase 5 — `SearchResultCard.java`

1. **CSS-only hover** — remove `getElement().addEventListener("mouseenter/mouseleave")`
   Java listeners. Add CSS class `result-card` and rely on `:hover` + `:active`.
2. **Larger download button** — remove `ButtonVariant.LUMO_SMALL` (or keep it and
   override min-height via CSS Phase 1 rule).
3. **Card footer separator** — add a thin border-top band using Aura's
   `--vaadin-border-color-secondary` to separate metadata from the button.
4. **Remove fixed dialog width** — delete `downloadDialog.setWidth("500px")`.
   CSS Phase 1 `max-width: min(460px, 95vw)` handles sizing.

### Phase 6 — `SearchResultCard.buildDownloadDialog`

1. Dialog width — handled via CSS (Phase 1), no Java change needed.
2. **Full-width footer buttons on mobile** — add class `dialog-footer` to the
   footer layout, then in CSS:
   ```css
   @media (max-width: 480px) {
     .dialog-footer { flex-direction: column-reverse; }
     .dialog-footer vaadin-button { width: 100%; }
   }
   ```
3. **Season/episode helper text** — the existing `setHelperText("Leave blank …")`
   is already correct; make sure it is visible (not clipped) on small screens.

### Phase 7 — `SettingsView.java`

1. **Card wrapping** — wrap each section (`tmdbForm`, `downloadForm`, etc.) in
   `vaadin-vertical-layout[theme~="card"]` using the `addThemeName("card")` API,
   or use a `Div` with the custom `card` CSS class.
2. **Collapsible sections** — use `vaadin-details` (Vaadin `Details` component)
   for each section so mobile users can expand only what they need:
   ```java
   Details tmdbSection = new Details("TMDB Configuration", tmdbForm, tmdbNote);
   tmdbSection.setOpened(true);
   ```
3. **Two-column form grid** — use `FormLayout` with `setResponsiveSteps` to
   show two columns on desktop, one on mobile:
   ```java
   downloadForm.setResponsiveSteps(
     new FormLayout.ResponsiveStep("0", 1),
     new FormLayout.ResponsiveStep("480px", 2)
   );
   ```

---

## File Change Summary

| File | Track | Change |
|------|-------|--------|
| `pom.xml` | A only | Bump Vaadin to 25, add `vaadin-aura-theme` |
| `frontend/index.html` | Both | Add Instrument Sans Google Font link |
| `frontend/themes/vixsrc/styles/theme.css` | Both | Replace `--lumo-*` overrides with Aura token values; add dark mode; fix dialog, tap targets, column classes |
| `MainLayout.java` | Both | Bottom nav bar for mobile; active page label in navbar |
| `SearchView.java` | Both | Vertical search bar on mobile; spinner; empty state; Aura grid gap |
| `DownloadQueueView.java` | Both | Fluid grid height; named column classes; status bar; mobile action buttons |
| `SearchResultCard.java` | Both | CSS-only hover; remove dialog fixed width; larger download button |
| `SettingsView.java` | Both | `Details` accordion sections; two-column `FormLayout` |

---

## Implementation Order

1. **Phase 1 — `theme.css`** — zero Java risk, immediately visible on all screens.
2. **Phase 4 — Queue view** — most painful on mobile today.
3. **Phase 3 — Search view** — primary entry point.
4. **Phase 5 & 6 — Cards & Dialog** — polishes the download flow.
5. **Phase 2 — Navigation** — bottom nav requires layout coordination.
6. **Phase 7 — Settings** — lowest traffic view.

If choosing Track A (Vaadin 25), do the version upgrade as an isolated step
before starting Phase 1, and run the full E2E suite to validate the upgrade
before any UI work begins.

---

## Out of Scope

- Backend API or download logic changes.
- New features beyond existing functionality.
- PWA offline mode.
- Changing the Vaadin version beyond what Track A specifies.

---

## References

- Aura source: `packages/aura/src/` in [`vaadin/web-components`](https://github.com/vaadin/web-components)
- Aura docs: https://vaadin.com/docs/latest/styling/themes/aura
- Aura color: https://vaadin.com/docs/latest/styling/themes/aura/color
- Lumo → Aura styling: https://blog.vaadin.com/how-to-style-vaadin-components-for-both-aura-and-lumo
- Vaadin 25 release: https://vaadin.com/blog/vaadin-25-0-release
