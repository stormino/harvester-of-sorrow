package com.github.stormino.view.component;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.combobox.MultiSelectComboBox;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.select.Select;
import com.vaadin.flow.component.textfield.IntegerField;
import com.github.stormino.model.ContentMetadata;
import com.github.stormino.model.DownloadTask;
import com.github.stormino.model.MediaSource;
import com.github.stormino.model.source.RaiPlayMetadata;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

public class SearchResultCard extends VerticalLayout {

    private static final List<String> ALL_LANGUAGES =
            List.of("en", "it", "es", "fr", "de", "pt", "ja", "ko", "ru", "zh");

    private final ContentMetadata content;
    private final DownloadTask.ContentType type;
    private final Supplier<Set<String>> defaultLanguagesSupplier;
    private final Supplier<String> defaultQualitySupplier;
    private final Set<String> supportedLanguages;
    private final DownloadHandler downloadHandler;

    // Built once per card; fields reset on each open to avoid Vaadin overlay ID conflicts
    private Dialog downloadDialog;
    private MultiSelectComboBox<String> dialogLanguageSelector;
    private Select<String> dialogQualitySelector;
    private IntegerField dialogSeasonField;
    private IntegerField dialogEpisodeField;

    public interface DownloadHandler {
        void onDownload(ContentMetadata content, DownloadTask.ContentType type,
                       Integer season, Integer episode,
                       Set<String> languages, String quality);
    }

    public SearchResultCard(ContentMetadata content,
                           DownloadTask.ContentType type,
                           Supplier<Set<String>> defaultLanguagesSupplier,
                           Supplier<String> defaultQualitySupplier,
                           Set<String> supportedLanguages,
                           DownloadHandler downloadHandler) {
        this.content = content;
        this.type = type;
        this.defaultLanguagesSupplier = defaultLanguagesSupplier;
        this.defaultQualitySupplier = defaultQualitySupplier;
        this.supportedLanguages = supportedLanguages;
        this.downloadHandler = downloadHandler;

        createCard();
    }

    private void createCard() {
        getElement().setAttribute("data-testid", "result-card");
        MediaSource cardSource = content.getSource() != null ? content.getSource() : MediaSource.VIXSRC;
        getElement().setAttribute("data-source", cardSource.name().toLowerCase());
        getElement().setAttribute("data-type", type.name());
        if (content.getTitle() != null) {
            getElement().setAttribute("data-title", content.getTitle());
        }

        String typeClass = type == DownloadTask.ContentType.MOVIE ? "result-card--movie" : "result-card--tv";
        addClassNames("result-card", typeClass);
        setSpacing(false);
        setPadding(false);
        getStyle()
                .set("padding", "var(--vaadin-gap-m, 0.75rem)")
                .set("gap", "0.4rem");

        // ── Title row ──────────────────────────────────────────────
        HorizontalLayout titleRow = new HorizontalLayout();
        titleRow.setSpacing(false);
        titleRow.setPadding(false);
        titleRow.setAlignItems(Alignment.CENTER);
        titleRow.getStyle().set("flex-wrap", "wrap").set("gap", "0.4rem");

        H3 title = new H3(content.getTitle());
        title.getStyle()
                .set("margin", "0")
                .set("line-height", "1.3")
                .set("font-size", "1rem")
                .set("font-weight", "600");
        titleRow.add(title);

        // Source badge — compact, visually secondary
        MediaSource source = content.getSource() != null ? content.getSource() : MediaSource.VIXSRC;
        Span sourceTag = new Span(source.getDisplayName());
        sourceTag.getStyle()
                .set("font-size", "0.6rem")
                .set("font-weight", "700")
                .set("letter-spacing", "0.04em")
                .set("padding", "0.1rem 0.35rem")
                .set("border-radius", "0.2rem")
                .set("line-height", "1.6")
                .set("align-self", "center")
                .set("white-space", "nowrap")
                .set("background", switch (source) {
                    case VIXSRC  -> "#1976D2";
                    case RAIPLAY -> "#0066B3";
                })
                .set("color", "#fff");
        titleRow.add(sourceTag);

        // TV-specific season/episode count chips
        if (type == DownloadTask.ContentType.TV && content.getNumberOfSeasons() != null
                && content.getNumberOfSeasons() > 0) {
            titleRow.add(smallChip(content.getNumberOfSeasons() + "S"));
            if (content.getTotalEpisodes() != null) {
                titleRow.add(smallChip(content.getTotalEpisodes() + "E"));
            }
        }

        // ── Metadata row: Year · Rating · TMDB ID ─────────────────
        HorizontalLayout metaRow = new HorizontalLayout();
        metaRow.setSpacing(false);
        metaRow.setPadding(false);
        metaRow.getStyle()
                .set("flex-wrap", "wrap")
                .set("gap", "0.5rem")
                .set("align-items", "center")
                .set("font-size", "var(--aura-font-size-s, 0.875rem)");

        if (content.getYear() != null) {
            Span year = new Span(content.getYear().toString());
            year.getStyle()
                    .set("color", "var(--vaadin-text-color-secondary)")
                    .set("font-weight", "500");
            metaRow.add(year);
        }

        if (content.getVoteAverage() != null) {
            Span rating = new Span(String.format("⭐ %.1f", content.getVoteAverage()));
            rating.getStyle().set("color", "#FFA000");
            metaRow.add(rating);
        }

        if (content.getTmdbId() != null) {
            Span tmdbId = new Span("ID: " + content.getTmdbId());
            tmdbId.getStyle()
                    .set("color", "var(--vaadin-text-color-secondary)")
                    .set("font-size", "var(--aura-font-size-xs, 0.75rem)");
            metaRow.add(tmdbId);
        }

        // ── Overview ───────────────────────────────────────────────
        Paragraph overview = new Paragraph(truncateOverview(content.getOverview()));
        overview.getStyle()
                .set("margin", "0")
                .set("line-height", "1.4")
                .set("color", "var(--vaadin-text-color-secondary)")
                .set("font-size", "var(--aura-font-size-s, 0.875rem)");

        // ── Download button ────────────────────────────────────────
        Button downloadBtn = new Button("Download");
        downloadBtn.addThemeVariants(ButtonVariant.LUMO_PRIMARY, ButtonVariant.LUMO_SMALL);
        downloadBtn.getElement().setAttribute("data-testid", "result-card-download");
        if (content.getTmdbId() != null) {
            downloadBtn.setId("result-card-download-" + content.getTmdbId());
        }
        downloadBtn.addClickListener(e -> openDownloadDialog());
        downloadBtn.getStyle().set("margin-top", "0.25rem");
        downloadBtn.getElement().addEventListener("click", e -> {}).addEventData("event.stopPropagation()");

        add(titleRow, metaRow, overview, downloadBtn);

        String externalUrl = externalUrlFor(source);
        if (externalUrl != null) {
            getElement().addEventListener("click", e ->
                    getElement().executeJs("window.open($0, '_blank')", externalUrl));
        }
    }

    private static Span smallChip(String text) {
        Span chip = new Span(text);
        chip.getStyle()
                .set("font-size", "0.6rem")
                .set("font-weight", "500")
                .set("padding", "0.1rem 0.35rem")
                .set("border-radius", "0.2rem")
                .set("line-height", "1.6")
                .set("align-self", "center")
                .set("background", "var(--vaadin-background-container-strong, rgba(0,0,0,0.08))")
                .set("color", "var(--vaadin-text-color-secondary)");
        return chip;
    }

    private String externalUrlFor(MediaSource source) {
        return switch (source) {
            case VIXSRC -> content.getTmdbId() == null ? null
                    : "https://www.themoviedb.org/"
                            + (type == DownloadTask.ContentType.MOVIE ? "movie" : "tv")
                            + "/" + content.getTmdbId();
            case RAIPLAY -> content.getSourceMetadata() instanceof RaiPlayMetadata m
                    ? m.webUrl() : null;
        };
    }

    private void openDownloadDialog() {
        if (downloadDialog == null) {
            buildDownloadDialog();
        }
        resetDialogFields();
        downloadDialog.open();
    }

    private void buildDownloadDialog() {
        downloadDialog = new Dialog();
        downloadDialog.setHeaderTitle("Download: " + content.getTitle());

        VerticalLayout layout = new VerticalLayout();
        layout.setPadding(false);
        layout.setSpacing(true);

        dialogLanguageSelector = new MultiSelectComboBox<>("Languages");
        dialogLanguageSelector.setId("dialog-language-selector");
        List<String> languageItems = ALL_LANGUAGES.stream()
                .filter(lang -> supportedLanguages == null
                        || supportedLanguages.isEmpty()
                        || supportedLanguages.contains(lang))
                .toList();
        dialogLanguageSelector.setItems(languageItems);
        dialogLanguageSelector.setWidthFull();

        dialogQualitySelector = new Select<>();
        dialogQualitySelector.setId("dialog-quality-selector");
        dialogQualitySelector.setLabel("Quality");
        dialogQualitySelector.setItems("best", "1080", "720", "worst");
        dialogQualitySelector.setWidthFull();

        layout.add(dialogLanguageSelector, dialogQualitySelector);

        if (type == DownloadTask.ContentType.TV) {
            dialogSeasonField = new IntegerField("Season");
            dialogSeasonField.setId("dialog-season-field");
            dialogSeasonField.setPlaceholder("All seasons");
            dialogSeasonField.setHelperText("Leave blank to download all seasons");
            dialogSeasonField.setMin(1);
            dialogSeasonField.setStepButtonsVisible(true);
            dialogSeasonField.setClearButtonVisible(true);
            dialogSeasonField.setWidthFull();

            dialogEpisodeField = new IntegerField("Episode");
            dialogEpisodeField.setId("dialog-episode-field");
            dialogEpisodeField.setPlaceholder("All episodes");
            dialogEpisodeField.setHelperText("Leave blank to download all episodes in season");
            dialogEpisodeField.setMin(1);
            dialogEpisodeField.setStepButtonsVisible(true);
            dialogEpisodeField.setClearButtonVisible(true);
            dialogEpisodeField.setWidthFull();

            layout.add(dialogSeasonField, dialogEpisodeField);

            Button confirmBtn = new Button("Add to Queue", e -> {
                downloadHandler.onDownload(
                        content, type,
                        dialogSeasonField.getValue(),
                        dialogEpisodeField.getValue(),
                        dialogLanguageSelector.getValue(),
                        dialogQualitySelector.getValue()
                );
                downloadDialog.close();
            });
            confirmBtn.setId("dialog-confirm-download");
            confirmBtn.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
            downloadDialog.getFooter().add(confirmBtn);
        } else {
            Button confirmBtn = new Button("Add to Queue", e -> {
                downloadHandler.onDownload(
                        content, type,
                        null, null,
                        dialogLanguageSelector.getValue(),
                        dialogQualitySelector.getValue()
                );
                downloadDialog.close();
            });
            confirmBtn.setId("dialog-confirm-download");
            confirmBtn.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
            downloadDialog.getFooter().add(confirmBtn);
        }

        Button cancelBtn = new Button("Cancel", e -> downloadDialog.close());
        downloadDialog.getFooter().add(cancelBtn);

        downloadDialog.add(layout);
    }

    private void resetDialogFields() {
        dialogQualitySelector.setValue(defaultQualitySupplier.get());
        dialogLanguageSelector.deselectAll();
        Set<String> defaultLanguages = defaultLanguagesSupplier.get();
        if (defaultLanguages != null && !defaultLanguages.isEmpty()) {
            Set<String> applicable = new LinkedHashSet<>(defaultLanguages);
            if (supportedLanguages != null && !supportedLanguages.isEmpty()) {
                applicable.retainAll(supportedLanguages);
            }
            List<String> languageItems = dialogLanguageSelector.getListDataView()
                    .getItems().toList();
            if (applicable.isEmpty() && !languageItems.isEmpty()) {
                applicable.add(languageItems.get(0));
            }
            applicable.forEach(dialogLanguageSelector::select);
        }
        if (dialogSeasonField != null) dialogSeasonField.clear();
        if (dialogEpisodeField != null) dialogEpisodeField.clear();
    }

    private String truncateOverview(String overview) {
        if (overview == null || overview.isBlank()) {
            return "No overview available.";
        }
        if (overview.length() > 120) {
            return overview.substring(0, 117) + "...";
        }
        return overview;
    }
}
