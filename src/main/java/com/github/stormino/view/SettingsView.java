package com.github.stormino.view;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.details.Details;
import com.vaadin.flow.component.formlayout.FormLayout;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.IntegerField;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.theme.lumo.LumoUtility;
import com.github.stormino.config.VixSrcProperties;

@Route(value = "settings", layout = MainLayout.class)
@PageTitle("Settings | VixSrc Downloader")
public class SettingsView extends VerticalLayout {

    private final VixSrcProperties properties;

    public SettingsView(VixSrcProperties properties) {
        this.properties = properties;

        setId("settings-view");
        setSizeFull();
        setPadding(true);
        setSpacing(true);

        H2 title = new H2("Settings");
        title.addClassNames(LumoUtility.Margin.Bottom.MEDIUM);

        add(title,
            buildTmdbSection(),
            buildDownloadSection(),
            buildExtractorSection(),
            buildSystemSection());
    }

    // ── TMDB ──────────────────────────────────────────────────────────────────

    private Details buildTmdbSection() {
        Paragraph info = new Paragraph(
                "TMDB API key is required for search functionality and metadata. " +
                "Get your free API key at: https://www.themoviedb.org/settings/api"
        );
        info.addClassNames(LumoUtility.TextColor.SECONDARY, LumoUtility.FontSize.SMALL);

        TextField tmdbApiKeyField = new TextField("API Key");
        tmdbApiKeyField.setValue(properties.getTmdb().getApiKey() != null
                ? properties.getTmdb().getApiKey() : "");
        tmdbApiKeyField.setWidthFull();
        tmdbApiKeyField.setPlaceholder("your_tmdb_api_key");

        FormLayout form = twoColumnForm();
        form.add(tmdbApiKeyField);
        form.setColspan(tmdbApiKeyField, 2);

        Paragraph note = new Paragraph("⚠️ Note: API key changes require application restart");
        note.addClassNames(
                LumoUtility.Background.WARNING_10,
                LumoUtility.Padding.SMALL,
                LumoUtility.BorderRadius.SMALL,
                LumoUtility.FontSize.SMALL
        );

        VerticalLayout content = new VerticalLayout(info, form, note);
        content.setPadding(false);
        content.setSpacing(true);

        Details panel = new Details("TMDB Configuration", content);
        panel.setOpened(true);
        panel.setWidthFull();
        return panel;
    }

    // ── Download ──────────────────────────────────────────────────────────────

    private Details buildDownloadSection() {
        TextField moviesPathField = new TextField("Movies Download Path");
        moviesPathField.setValue(properties.getDownload().getMoviesPath());
        moviesPathField.setWidthFull();
        moviesPathField.setReadOnly(true);

        TextField tvShowsPathField = new TextField("TV Shows Download Path");
        tvShowsPathField.setValue(properties.getDownload().getTvShowsPath());
        tvShowsPathField.setWidthFull();
        tvShowsPathField.setReadOnly(true);

        TextField tempPathField = new TextField("Temporary Path");
        tempPathField.setValue(properties.getDownload().getTempPath());
        tempPathField.setWidthFull();
        tempPathField.setReadOnly(true);

        IntegerField parallelDownloadsField = new IntegerField("Parallel Downloads");
        parallelDownloadsField.setValue(properties.getDownload().getParallelDownloads());
        parallelDownloadsField.setMin(1);
        parallelDownloadsField.setMax(10);
        parallelDownloadsField.setStepButtonsVisible(true);
        parallelDownloadsField.setReadOnly(true);

        IntegerField segmentConcurrencyField = new IntegerField("Segment Concurrency");
        segmentConcurrencyField.setValue(properties.getDownload().getSegmentConcurrency());
        segmentConcurrencyField.setMin(1);
        segmentConcurrencyField.setMax(20);
        segmentConcurrencyField.setStepButtonsVisible(true);
        segmentConcurrencyField.setReadOnly(true);

        TextField defaultQualityField = new TextField("Default Quality");
        defaultQualityField.setValue(properties.getDownload().getDefaultQuality());
        defaultQualityField.setReadOnly(true);

        TextField defaultLanguageField = new TextField("Default Language");
        defaultLanguageField.setValue(properties.getDownload().getDefaultLanguage());
        defaultLanguageField.setReadOnly(true);

        FormLayout form = twoColumnForm();
        // Path fields are long — span both columns
        form.add(moviesPathField);  form.setColspan(moviesPathField, 2);
        form.add(tvShowsPathField); form.setColspan(tvShowsPathField, 2);
        form.add(tempPathField);    form.setColspan(tempPathField, 2);
        // Numeric and short fields share rows
        form.add(parallelDownloadsField, segmentConcurrencyField,
                 defaultQualityField, defaultLanguageField);

        Paragraph note = new Paragraph(
                "⚠️ Download configuration is read-only. " +
                "Update via environment variables or application.yml and restart."
        );
        note.addClassNames(
                LumoUtility.Background.WARNING_10,
                LumoUtility.Padding.SMALL,
                LumoUtility.BorderRadius.SMALL,
                LumoUtility.FontSize.SMALL
        );

        VerticalLayout content = new VerticalLayout(form, note);
        content.setPadding(false);
        content.setSpacing(true);

        Details panel = new Details("Download Configuration", content);
        panel.setOpened(true);
        panel.setWidthFull();
        return panel;
    }

    // ── Extractor ─────────────────────────────────────────────────────────────

    private Details buildExtractorSection() {
        TextField baseUrlField = new TextField("VixSrc Base URL");
        baseUrlField.setValue(properties.getExtractor().getBaseUrl());
        baseUrlField.setWidthFull();
        baseUrlField.setReadOnly(true);

        IntegerField timeoutField = new IntegerField("Timeout (seconds)");
        timeoutField.setValue(properties.getExtractor().getTimeoutSeconds());
        timeoutField.setReadOnly(true);

        FormLayout form = twoColumnForm();
        form.add(baseUrlField); form.setColspan(baseUrlField, 2);
        form.add(timeoutField);

        Details panel = new Details("Extractor Configuration", form);
        panel.setWidthFull();
        return panel;
    }

    // ── System ────────────────────────────────────────────────────────────────

    private Details buildSystemSection() {
        VerticalLayout info = new VerticalLayout();
        info.setPadding(false);
        info.setSpacing(false);
        info.add(
                createInfoRow("Java Version", System.getProperty("java.version")),
                createInfoRow("OS", System.getProperty("os.name") + " " + System.getProperty("os.version")),
                createInfoRow("Available Processors", String.valueOf(Runtime.getRuntime().availableProcessors()))
        );

        Button checkToolsBtn = new Button("Check Tools", e -> checkTools());
        checkToolsBtn.addThemeVariants(ButtonVariant.LUMO_PRIMARY);

        VerticalLayout content = new VerticalLayout(info, checkToolsBtn);
        content.setPadding(false);
        content.setSpacing(true);

        Details panel = new Details("System Information", content);
        panel.setWidthFull();
        return panel;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static FormLayout twoColumnForm() {
        FormLayout form = new FormLayout();
        form.setResponsiveSteps(
                new FormLayout.ResponsiveStep("0", 1),
                new FormLayout.ResponsiveStep("480px", 2)
        );
        return form;
    }

    private Paragraph createInfoRow(String label, String value) {
        Paragraph p = new Paragraph(label + ": " + value);
        p.addClassNames(LumoUtility.Margin.Vertical.XSMALL);
        return p;
    }

    private void checkTools() {
        StringBuilder message = new StringBuilder("Tool Check Results:\n\n");

        boolean ffmpegAvailable = checkCommand("ffmpeg");
        message.append(ffmpegAvailable ? "✓ ffmpeg: Available\n" : "✗ ffmpeg: Not found\n");

        if (!ffmpegAvailable) {
            message.append("\n⚠️ ffmpeg not found! Please install ffmpeg.");
            Notification.show(message.toString(), 5000, Notification.Position.MIDDLE)
                    .addThemeVariants(NotificationVariant.LUMO_ERROR);
        } else {
            Notification.show(message.toString(), 5000, Notification.Position.MIDDLE)
                    .addThemeVariants(NotificationVariant.LUMO_SUCCESS);
        }
    }

    private boolean checkCommand(String command) {
        try {
            Process process = new ProcessBuilder(command, "--version")
                    .redirectErrorStream(true)
                    .start();
            return process.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
