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
        MediaSource source = content.getSource() != null ? content.getSource() : MediaSource.VIXSRC;
        getElement().setAttribute("data-source", source.name().toLowerCase());
        getElement().setAttribute("data-type", type.name());
        if (content.getTitle() != null) {
            getElement().setAttribute("data-title", content.getTitle());
        }

        setSpacing(false);
        setPadding(true);
        getStyle().set("gap", "0.4rem");

        // ── Title row ──────────────────────────────────────────────
        HorizontalLayout titleRow = new HorizontalLayout();
        titleRow.setSpacing(false);
        titleRow.setPadding(false);
        titleRow.setAlignItems(Alignment.CENTER);
        titleRow.getStyle().set("flex-wrap", "wrap").set("gap", "0.4rem");

        H3 title = new H3(content.getTitle());
        title.getStyle().set("margin", "0");
        titleRow.add(title);

        // Source badge using Vaadin theme attribute
        Span sourceTag = new Span(source.getDisplayName());
        sourceTag.getElement().getThemeList().add("badge");
        sourceTag.getElement().getThemeList().add("primary");
        titleRow.add(sourceTag);

        // TV season/episode chips
        if (type == DownloadTask.ContentType.TV && content.getNumberOfSeasons() != null
                && content.getNumberOfSeasons() > 0) {
            titleRow.add(chip(content.getNumberOfSeasons() + "S"));
            if (content.getTotalEpisodes() != null) {
                titleRow.add(chip(content.getTotalEpisodes() + "E"));
            }
        }

        // ── Metadata row ───────────────────────────────────────────
        HorizontalLayout metaRow = new HorizontalLayout();
        metaRow.setSpacing(false);
        metaRow.setPadding(false);
        metaRow.getStyle().set("flex-wrap", "wrap").set("gap", "0.5rem").set("align-items", "center");

        if (content.getYear() != null) {
            metaRow.add(new Span(content.getYear().toString()));
        }
        if (content.getVoteAverage() != null) {
            metaRow.add(new Span(String.format("⭐ %.1f", content.getVoteAverage())));
        }
        if (content.getTmdbId() != null) {
            Span tmdbId = new Span("ID: " + content.getTmdbId());
            tmdbId.getElement().getThemeList().add("badge");
            tmdbId.getElement().getThemeList().add("contrast");
            metaRow.add(tmdbId);
        }

        // ── Overview ───────────────────────────────────────────────
        Paragraph overview = new Paragraph(truncateOverview(content.getOverview()));
        overview.getStyle().set("margin", "0");

        // ── Download button ────────────────────────────────────────
        Button downloadBtn = new Button("Download");
        downloadBtn.addThemeVariants(ButtonVariant.LUMO_PRIMARY, ButtonVariant.LUMO_SMALL);
        downloadBtn.getElement().setAttribute("data-testid", "result-card-download");
        if (content.getTmdbId() != null) {
            downloadBtn.setId("result-card-download-" + content.getTmdbId());
        }
        downloadBtn.addClickListener(e -> openDownloadDialog());
        downloadBtn.getElement().addEventListener("click", e -> {}).addEventData("event.stopPropagation()");

        add(titleRow, metaRow, overview, downloadBtn);

        String externalUrl = externalUrlFor(source);
        if (externalUrl != null) {
            getElement().addEventListener("click", e ->
                    getElement().executeJs("window.open($0, '_blank')", externalUrl));
        }
    }

    private static Span chip(String text) {
        Span chip = new Span(text);
        chip.getElement().getThemeList().add("badge");
        chip.getElement().getThemeList().add("contrast");
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
                downloadHandler.onDownload(content, type,
                        dialogSeasonField.getValue(), dialogEpisodeField.getValue(),
                        dialogLanguageSelector.getValue(), dialogQualitySelector.getValue());
                downloadDialog.close();
            });
            confirmBtn.setId("dialog-confirm-download");
            confirmBtn.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
            downloadDialog.getFooter().add(confirmBtn);
        } else {
            Button confirmBtn = new Button("Add to Queue", e -> {
                downloadHandler.onDownload(content, type, null, null,
                        dialogLanguageSelector.getValue(), dialogQualitySelector.getValue());
                downloadDialog.close();
            });
            confirmBtn.setId("dialog-confirm-download");
            confirmBtn.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
            downloadDialog.getFooter().add(confirmBtn);
        }

        downloadDialog.getFooter().add(new Button("Cancel", e -> downloadDialog.close()));
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
            List<String> items = dialogLanguageSelector.getListDataView().getItems().toList();
            if (applicable.isEmpty() && !items.isEmpty()) applicable.add(items.get(0));
            applicable.forEach(dialogLanguageSelector::select);
        }
        if (dialogSeasonField != null) dialogSeasonField.clear();
        if (dialogEpisodeField != null) dialogEpisodeField.clear();
    }

    private String truncateOverview(String overview) {
        if (overview == null || overview.isBlank()) return "No overview available.";
        return overview.length() > 120 ? overview.substring(0, 117) + "..." : overview;
    }
}
