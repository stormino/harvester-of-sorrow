package com.github.stormino.view.component;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.combobox.MultiSelectComboBox;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.select.Select;
import com.vaadin.flow.component.textfield.IntegerField;
import com.vaadin.flow.theme.lumo.LumoUtility;
import com.github.stormino.model.ContentMetadata;
import com.github.stormino.model.DownloadTask;
import com.github.stormino.model.MediaSource;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

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

    public interface DownloadHandler {
        void onDownload(ContentMetadata content, DownloadTask.ContentType type,
                       Integer season, Integer episode,
                       Set<String> languages, String quality,
                       boolean includeAudioDescription);
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
        if (type == DownloadTask.ContentType.TV && content.getNumberOfSeasons() != null) {
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
        downloadBtn.addClickListener(e -> openDownloadDialog());
        downloadBtn.getStyle().set("margin-top", "0.25rem");
        downloadBtn.getElement().addEventListener("click", e -> {}).addEventData("event.stopPropagation()");

        add(titleRow, metaRow, overview, downloadBtn);

        // Make card clickable to open TMDB page (only for sources keyed off TMDB)
        if (source == MediaSource.VIXSRC && content.getTmdbId() != null) {
            String tmdbPath = type == DownloadTask.ContentType.MOVIE ? "movie" : "tv";
            String tmdbUrl = "https://www.themoviedb.org/" + tmdbPath + "/" + content.getTmdbId();
            getElement().addEventListener("click", e ->
                    getElement().executeJs("window.open($0, '_blank')", tmdbUrl));
        }
    }
    
    private void openDownloadDialog() {
        Dialog dialog = new Dialog();
        dialog.setHeaderTitle("Download: " + content.getTitle());
        dialog.setWidth("500px");
        
        VerticalLayout layout = new VerticalLayout();
        layout.setPadding(false);
        layout.setSpacing(true);
        
        // Language selector — restricted to languages this source actually serves
        MultiSelectComboBox<String> languageSelector = new MultiSelectComboBox<>("Languages");
        List<String> languageItems = ALL_LANGUAGES.stream()
                .filter(lang -> supportedLanguages == null
                        || supportedLanguages.isEmpty()
                        || supportedLanguages.contains(lang))
                .toList();
        languageSelector.setItems(languageItems);
        languageSelector.setWidthFull();

        // Quality selector
        Select<String> qualitySelector = new Select<>();
        qualitySelector.setLabel("Quality");
        qualitySelector.setItems("best", "1080", "720", "worst");
        qualitySelector.setValue(defaultQualitySupplier.get());
        qualitySelector.setWidthFull();

        // Audio-description checkbox — off by default
        Checkbox audioDescriptionCheckbox = new Checkbox("Include audio description");
        audioDescriptionCheckbox.getElement().setAttribute(
                "title", "Adds the accessibility audio track if available in the playlist.");

        layout.add(languageSelector, qualitySelector, audioDescriptionCheckbox);

        // Set default language after adding to layout, intersecting with supported set
        Set<String> defaultLanguages = defaultLanguagesSupplier.get();
        if (defaultLanguages != null && !defaultLanguages.isEmpty()) {
            Set<String> applicable = new LinkedHashSet<>(defaultLanguages);
            if (supportedLanguages != null && !supportedLanguages.isEmpty()) {
                applicable.retainAll(supportedLanguages);
            }
            if (applicable.isEmpty() && !languageItems.isEmpty()) {
                // Fallback so the user always sees at least one selection
                applicable.add(languageItems.get(0));
            }
            applicable.forEach(languageSelector::select);
        }
        
        // TV-specific: Season/Episode selectors
        if (type == DownloadTask.ContentType.TV) {
            IntegerField seasonField = new IntegerField("Season");
            seasonField.setPlaceholder("All seasons");
            seasonField.setHelperText("Leave blank to download all seasons");
            seasonField.setMin(1);
            seasonField.setStepButtonsVisible(true);
            seasonField.setClearButtonVisible(true);
            seasonField.setWidthFull();

            IntegerField episodeField = new IntegerField("Episode");
            episodeField.setPlaceholder("All episodes");
            episodeField.setHelperText("Leave blank to download all episodes in season");
            episodeField.setMin(1);
            episodeField.setStepButtonsVisible(true);
            episodeField.setClearButtonVisible(true);
            episodeField.setWidthFull();

            layout.add(seasonField, episodeField);
            
            // Download button
            Button downloadBtn = new Button("Add to Queue", e -> {
                downloadHandler.onDownload(
                        content,
                        type,
                        seasonField.getValue(),
                        episodeField.getValue(),
                        languageSelector.getValue(),
                        qualitySelector.getValue(),
                        audioDescriptionCheckbox.getValue()
                );
                dialog.close();
            });
            downloadBtn.addThemeVariants(ButtonVariant.LUMO_PRIMARY);

            dialog.getFooter().add(downloadBtn);

        } else {
            // Movie: Direct download
            Button downloadBtn = new Button("Add to Queue", e -> {
                downloadHandler.onDownload(
                        content,
                        type,
                        null,
                        null,
                        languageSelector.getValue(),
                        qualitySelector.getValue(),
                        audioDescriptionCheckbox.getValue()
                );
                dialog.close();
            });
            downloadBtn.addThemeVariants(ButtonVariant.LUMO_PRIMARY);

            dialog.getFooter().add(downloadBtn);
        }
        
        Button cancelBtn = new Button("Cancel", e -> dialog.close());
        dialog.getFooter().add(cancelBtn);
        
        dialog.add(layout);
        dialog.open();
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
