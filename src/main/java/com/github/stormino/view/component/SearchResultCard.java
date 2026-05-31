package com.github.stormino.view.component;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.combobox.MultiSelectComboBox;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.select.Select;
import com.vaadin.flow.theme.lumo.LumoUtility;
import com.github.stormino.model.ContentMetadata;
import com.github.stormino.model.DownloadTask;
import com.github.stormino.model.MediaSource;
import com.github.stormino.model.source.RaiPlayMetadata;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.IntStream;

/**
 * Card component for displaying search results
 */
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
    private MultiSelectComboBox<Integer> dialogSeasonSelector;
    private MultiSelectComboBox<Integer> dialogEpisodeSelector;

    public interface DownloadHandler {
        void onDownload(ContentMetadata content, DownloadTask.ContentType type,
                       Set<Integer> seasons, Set<Integer> episodes,
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

        addClassNames(
                LumoUtility.BorderRadius.MEDIUM,
                LumoUtility.Padding.SMALL,
                LumoUtility.BoxShadow.SMALL
        );
        setSpacing(false);
        getStyle()
                .set("gap", "0.5rem")
                .set("transition", "transform 0.2s ease, box-shadow 0.2s ease")
                .set("cursor", "pointer");

        // Set background gradient based on content type with hover effect
        if (type == DownloadTask.ContentType.MOVIE) {
            getStyle().set("background", "linear-gradient(135deg, #E3F2FD 0%, #BBDEFB 100%)"); // Modern blue gradient
            getElement().addEventListener("mouseenter", e ->
                getStyle().set("box-shadow", "0 8px 16px rgba(33, 150, 243, 0.25)")
                        .set("transform", "translateY(-2px)"));
            getElement().addEventListener("mouseleave", e ->
                getStyle().set("box-shadow", "0 2px 4px rgba(0, 0, 0, 0.1)")
                        .set("transform", "translateY(0)"));
        } else {
            getStyle().set("background", "linear-gradient(135deg, #FCE4EC 0%, #F8BBD0 100%)"); // Modern pink gradient
            getElement().addEventListener("mouseenter", e ->
                getStyle().set("box-shadow", "0 8px 16px rgba(233, 30, 99, 0.25)")
                        .set("transform", "translateY(-2px)"));
            getElement().addEventListener("mouseleave", e ->
                getStyle().set("box-shadow", "0 2px 4px rgba(0, 0, 0, 0.1)")
                        .set("transform", "translateY(0)"));
        }

        // Title row
        HorizontalLayout titleRow = new HorizontalLayout();
        titleRow.setSpacing(false);
        titleRow.setAlignItems(Alignment.CENTER);
        titleRow.addClassNames(LumoUtility.Gap.XSMALL);
        titleRow.setPadding(false);
        titleRow.getStyle().set("flex-wrap", "wrap");

        H3 title = new H3(content.getTitle());
        title.addClassNames(LumoUtility.Margin.NONE);
        title.getStyle()
                .set("line-height", "1.3")
                .set("font-weight", "600");
        titleRow.add(title);

        // Source tag
        MediaSource source = content.getSource() != null ? content.getSource() : MediaSource.VIXSRC;
        Span sourceTag = new Span(source.getDisplayName());
        sourceTag.addClassNames(LumoUtility.FontSize.XSMALL);
        sourceTag.getStyle()
                .set("background", switch (source) {
                    case VIXSRC -> "#1976D2";
                    case RAIPLAY -> "#0066B3";
                })
                .set("color", "#fff")
                .set("padding", "0.15rem 0.4rem")
                .set("border-radius", "0.25rem")
                .set("font-weight", "600");
        titleRow.add(sourceTag);

        // TV-specific info as badge
        if (type == DownloadTask.ContentType.TV && content.getNumberOfSeasons() != null
                && content.getNumberOfSeasons() > 0) {
            Span badge = new Span(content.getNumberOfSeasons() + "S");
            badge.addClassNames(LumoUtility.FontSize.XSMALL, LumoUtility.TextColor.SECONDARY);
            badge.getStyle()
                    .set("background", "rgba(0,0,0,0.1)")
                    .set("padding", "0.15rem 0.4rem")
                    .set("border-radius", "0.25rem")
                    .set("font-weight", "500");
            titleRow.add(badge);

            if (content.getTotalEpisodes() != null) {
                Span epBadge = new Span(content.getTotalEpisodes() + "E");
                epBadge.addClassNames(LumoUtility.FontSize.XSMALL, LumoUtility.TextColor.SECONDARY);
                epBadge.getStyle()
                        .set("background", "rgba(0,0,0,0.1)")
                        .set("padding", "0.15rem 0.4rem")
                        .set("border-radius", "0.25rem")
                        .set("font-weight", "500");
                titleRow.add(epBadge);
            }
        }

        // Metadata row: Year, Rating, TMDB ID
        HorizontalLayout metaRow = new HorizontalLayout();
        metaRow.setSpacing(false);
        metaRow.addClassNames(LumoUtility.Gap.SMALL, LumoUtility.FontSize.SMALL);
        metaRow.setPadding(false);
        metaRow.getStyle().set("flex-wrap", "wrap");

        if (content.getYear() != null) {
            Span year = new Span(content.getYear().toString());
            year.addClassNames(LumoUtility.TextColor.SECONDARY);
            year.getStyle().set("font-weight", "500");
            metaRow.add(year);
        }

        if (content.getVoteAverage() != null) {
            Span rating = new Span(String.format("⭐ %.1f", content.getVoteAverage()));
            rating.getStyle().set("color", "#FFA000");
            metaRow.add(rating);
        }

        if (content.getTmdbId() != null) {
            Span tmdbId = new Span("ID: " + content.getTmdbId());
            tmdbId.addClassNames(LumoUtility.TextColor.TERTIARY, LumoUtility.FontSize.XSMALL);
            metaRow.add(tmdbId);
        }
        
        // Overview
        Paragraph overview = new Paragraph(truncateOverview(content.getOverview()));
        overview.addClassNames(
                LumoUtility.TextColor.SECONDARY,
                LumoUtility.FontSize.SMALL
        );
        overview.getStyle()
                .set("margin", "0")
                .set("line-height", "1.4");

        // Download button
        Button downloadBtn = new Button("Download", VaadinIcon.DOWNLOAD.create());
        downloadBtn.addThemeVariants(ButtonVariant.LUMO_PRIMARY, ButtonVariant.LUMO_SMALL);
        downloadBtn.getElement().setAttribute("data-testid", "result-card-download");
        if (content.getTmdbId() != null) {
            downloadBtn.setId("result-card-download-" + content.getTmdbId());
        }
        downloadBtn.addClickListener(e -> openDownloadDialog());
        downloadBtn.getStyle().set("margin-top", "0.25rem");
        downloadBtn.getElement().addEventListener("click", e -> {}).addEventData("event.stopPropagation()");

        add(titleRow, metaRow, overview, downloadBtn);

        // Make card clickable to open the source's public web page.
        String externalUrl = externalUrlFor(source);
        if (externalUrl != null) {
            getElement().addEventListener("click", e ->
                    getElement().executeJs("window.open($0, '_blank')", externalUrl));
        }
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
        downloadDialog.setWidth("500px");

        VerticalLayout layout = new VerticalLayout();
        layout.setPadding(false);
        layout.setSpacing(true);

        // Language selector — restricted to languages this source actually serves
        dialogLanguageSelector = new MultiSelectComboBox<>("Languages");
        dialogLanguageSelector.setId("dialog-language-selector");
        List<String> languageItems = ALL_LANGUAGES.stream()
                .filter(lang -> supportedLanguages == null
                        || supportedLanguages.isEmpty()
                        || supportedLanguages.contains(lang))
                .toList();
        dialogLanguageSelector.setItems(languageItems);
        dialogLanguageSelector.setWidthFull();

        // Quality selector
        dialogQualitySelector = new Select<>();
        dialogQualitySelector.setId("dialog-quality-selector");
        dialogQualitySelector.setLabel("Quality");
        dialogQualitySelector.setItems("best", "1080", "720", "worst");
        dialogQualitySelector.setWidthFull();

        layout.add(dialogLanguageSelector, dialogQualitySelector);

        // TV-specific: Season/Episode multi-selectors
        if (type == DownloadTask.ContentType.TV) {
            dialogSeasonSelector = new MultiSelectComboBox<>("Seasons");
            dialogSeasonSelector.setId("dialog-season-field");
            dialogSeasonSelector.setPlaceholder("All seasons");
            dialogSeasonSelector.setHelperText("Leave empty to download all seasons");
            dialogSeasonSelector.setWidthFull();
            dialogSeasonSelector.setAllowCustomValue(true);
            dialogSeasonSelector.addCustomValueSetListener(e -> {
                try {
                    int val = Integer.parseInt(e.getDetail());
                    if (val >= 1) dialogSeasonSelector.select(val);
                } catch (NumberFormatException ignored) {}
            });
            if (content.getNumberOfSeasons() != null && content.getNumberOfSeasons() > 0) {
                List<Integer> seasons = IntStream.rangeClosed(1, content.getNumberOfSeasons())
                        .boxed().toList();
                dialogSeasonSelector.setItems(seasons);
            }

            dialogEpisodeSelector = new MultiSelectComboBox<>("Episodes");
            dialogEpisodeSelector.setId("dialog-episode-field");
            dialogEpisodeSelector.setPlaceholder("All episodes");
            dialogEpisodeSelector.setHelperText("Leave empty to download all episodes in selected seasons");
            dialogEpisodeSelector.setWidthFull();
            dialogEpisodeSelector.setAllowCustomValue(true);
            dialogEpisodeSelector.addCustomValueSetListener(e -> {
                try {
                    int val = Integer.parseInt(e.getDetail());
                    if (val >= 1) dialogEpisodeSelector.select(val);
                } catch (NumberFormatException ignored) {}
            });

            layout.add(dialogSeasonSelector, dialogEpisodeSelector);

            Button downloadBtn = new Button("Add to Queue", e -> {
                downloadHandler.onDownload(
                        content, type,
                        dialogSeasonSelector.getValue(),
                        dialogEpisodeSelector.getValue(),
                        dialogLanguageSelector.getValue(),
                        dialogQualitySelector.getValue()
                );
                downloadDialog.close();
            });
            downloadBtn.setId("dialog-confirm-download");
            downloadBtn.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
            downloadDialog.getFooter().add(downloadBtn);

        } else {
            Button downloadBtn = new Button("Add to Queue", e -> {
                downloadHandler.onDownload(
                        content, type,
                        null, null,
                        dialogLanguageSelector.getValue(),
                        dialogQualitySelector.getValue()
                );
                downloadDialog.close();
            });
            downloadBtn.setId("dialog-confirm-download");
            downloadBtn.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
            downloadDialog.getFooter().add(downloadBtn);
        }

        Button cancelBtn = new Button("Cancel", e -> downloadDialog.close());
        downloadDialog.getFooter().add(cancelBtn);

        downloadDialog.add(layout);
    }

    private void resetDialogFields() {
        // Reset quality to current default
        dialogQualitySelector.setValue(defaultQualitySupplier.get());

        // Reset language to current default, intersected with supported languages
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

        // Clear season/episode so a previous selection never bleeds into the next open
        if (dialogSeasonSelector != null) dialogSeasonSelector.deselectAll();
        if (dialogEpisodeSelector != null) dialogEpisodeSelector.deselectAll();
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
