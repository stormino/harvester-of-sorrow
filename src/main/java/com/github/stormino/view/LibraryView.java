package com.github.stormino.view;

import com.github.stormino.model.ContentMetadata;
import com.github.stormino.model.ContentTypeFilter;
import com.github.stormino.model.MediaSource;
import com.github.stormino.model.MonitoredShow;
import com.github.stormino.model.source.SourceMetadata;
import com.github.stormino.service.MonitoringService;
import com.github.stormino.service.MonitoringService.LibraryEntry;
import com.github.stormino.service.source.MediaSourceProvider;
import com.github.stormino.service.source.MediaSourceRegistry;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.confirmdialog.ConfirmDialog;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.data.renderer.ComponentRenderer;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.theme.lumo.LumoUtility;
import lombok.extern.slf4j.Slf4j;

import java.time.format.DateTimeFormatter;
import java.util.List;

@Slf4j
@Route(value = "library", layout = MainLayout.class)
@PageTitle("Library | VixSrc Downloader")
public class LibraryView extends VerticalLayout {

    private static final DateTimeFormatter DT_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final MonitoringService monitoringService;
    private final MediaSourceRegistry sourceRegistry;

    private final Grid<LibraryEntry> grid;

    public LibraryView(MonitoringService monitoringService,
                       MediaSourceRegistry sourceRegistry) {
        this.monitoringService = monitoringService;
        this.sourceRegistry = sourceRegistry;

        setSizeFull();
        setPadding(true);
        setSpacing(true);

        H2 title = new H2("TV Show Library");
        title.setId("library-title");
        title.addClassNames(LumoUtility.Margin.Bottom.NONE);

        Paragraph subtitle = new Paragraph(
                "Downloaded TV shows detected on disk. Add them to monitoring to automatically enqueue new episodes.");
        subtitle.addClassNames(LumoUtility.TextColor.SECONDARY, LumoUtility.Margin.Top.NONE);

        Button refreshButton = new Button("Refresh", VaadinIcon.REFRESH.create());
        refreshButton.setId("library-refresh-button");
        refreshButton.addClickListener(e -> refresh());

        HorizontalLayout header = new HorizontalLayout(title, refreshButton);
        header.setWidthFull();
        header.setDefaultVerticalComponentAlignment(FlexComponent.Alignment.CENTER);
        header.expand(title);

        grid = new Grid<>();
        grid.setId("library-grid");
        grid.setWidthFull();
        grid.setAllRowsVisible(false);
        grid.getStyle().set("flex-grow", "1");

        grid.addColumn(LibraryEntry::directoryName)
                .setHeader("Show Directory")
                .setFlexGrow(3)
                .setSortable(true)
                .setKey("directory");

        grid.addColumn(LibraryEntry::seasonCount)
                .setHeader("Seasons")
                .setFlexGrow(1)
                .setSortable(true)
                .setKey("seasons");

        grid.addColumn(LibraryEntry::episodeCount)
                .setHeader("Episodes")
                .setFlexGrow(1)
                .setSortable(true)
                .setKey("episodes");

        grid.addColumn(new ComponentRenderer<>(entry -> {
            if (!entry.isMonitored()) {
                Span badge = new Span("Not monitored");
                badge.getElement().getThemeList().add("badge contrast");
                return badge;
            }
            MonitoredShow show = entry.monitoredShow();
            Span badge = new Span(show.isEnabled() ? "Monitoring" : "Paused");
            badge.getElement().getThemeList().add(show.isEnabled() ? "badge success" : "badge");
            return badge;
        })).setHeader("Status").setFlexGrow(1).setKey("status");

        grid.addColumn(new ComponentRenderer<>(entry -> {
            if (!entry.isMonitored()) return new Span("-");
            MonitoredShow show = entry.monitoredShow();
            return new Span(show.getSource().getDisplayName());
        })).setHeader("Source").setFlexGrow(1).setKey("source");

        grid.addColumn(new ComponentRenderer<>(entry -> {
            if (!entry.isMonitored() || entry.monitoredShow().getLastCheckedAt() == null) {
                return new Span("-");
            }
            return new Span(entry.monitoredShow().getLastCheckedAt().format(DT_FORMAT));
        })).setHeader("Last Checked").setFlexGrow(2).setKey("lastChecked");

        grid.addColumn(new ComponentRenderer<>(this::buildActionsColumn))
                .setHeader("Actions")
                .setFlexGrow(2)
                .setKey("actions");

        add(header, subtitle, grid);
        setFlexGrow(1, grid);

        refresh();
    }

    private HorizontalLayout buildActionsColumn(LibraryEntry entry) {
        HorizontalLayout actions = new HorizontalLayout();
        actions.setSpacing(true);
        actions.setPadding(false);

        if (!entry.isMonitored()) {
            Button addBtn = new Button("Monitor", VaadinIcon.BELL.create());
            addBtn.addThemeVariants(ButtonVariant.LUMO_PRIMARY, ButtonVariant.LUMO_SMALL);
            addBtn.addClickListener(e -> openConfigureDialog(entry, null));
            actions.add(addBtn);
        } else {
            MonitoredShow show = entry.monitoredShow();

            Button editBtn = new Button("Edit", VaadinIcon.EDIT.create());
            editBtn.addThemeVariants(ButtonVariant.LUMO_SMALL);
            editBtn.addClickListener(e -> openConfigureDialog(entry, show));

            Button toggleBtn;
            if (show.isEnabled()) {
                toggleBtn = new Button("Pause", VaadinIcon.PAUSE.create());
                toggleBtn.addThemeVariants(ButtonVariant.LUMO_SMALL);
                toggleBtn.addClickListener(e -> {
                    monitoringService.setEnabled(show.getId(), false);
                    showNotification("Monitoring paused for " + show.getTitle(), false);
                    refresh();
                });
            } else {
                toggleBtn = new Button("Resume", VaadinIcon.PLAY.create());
                toggleBtn.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_SUCCESS);
                toggleBtn.addClickListener(e -> {
                    monitoringService.setEnabled(show.getId(), true);
                    showNotification("Monitoring resumed for " + show.getTitle(), false);
                    refresh();
                });
            }

            Button checkBtn = new Button("Check now", VaadinIcon.SEARCH.create());
            checkBtn.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY);
            checkBtn.setId("check-now-" + show.getId());
            checkBtn.addClickListener(e -> {
                checkBtn.setEnabled(false);
                UI ui = UI.getCurrent();
                Thread.ofVirtual().start(() -> {
                    int count = monitoringService.checkForNewEpisodes(show);
                    ui.access(() -> {
                        if (count > 0) {
                            showNotification(count + " new episode(s) enqueued for " + show.getTitle(), false);
                        } else {
                            showNotification("No new episodes found for " + show.getTitle(), false);
                        }
                        refresh();
                    });
                });
            });

            Button removeBtn = new Button(VaadinIcon.TRASH.create());
            removeBtn.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_ERROR, ButtonVariant.LUMO_TERTIARY);
            removeBtn.addClickListener(e -> confirmRemove(show));

            actions.add(editBtn, toggleBtn, checkBtn, removeBtn);
        }
        return actions;
    }

    private void openConfigureDialog(LibraryEntry entry, MonitoredShow existing) {
        Dialog dialog = new Dialog();
        dialog.setWidth("480px");
        dialog.setHeaderTitle(existing == null
                ? "Configure monitoring — " + entry.directoryName()
                : "Edit monitoring — " + entry.directoryName());

        VerticalLayout content = new VerticalLayout();
        content.setPadding(false);
        content.setSpacing(true);

        // Source selector
        ComboBox<MediaSource> sourceSelect = new ComboBox<>("Source");
        sourceSelect.setId("monitor-source-select");
        sourceSelect.setItems(MediaSource.values());
        sourceSelect.setItemLabelGenerator(MediaSource::getDisplayName);
        sourceSelect.setValue(existing != null ? existing.getSource() : MediaSource.VIXSRC);
        sourceSelect.setWidthFull();

        // Search field
        TextField searchField = new TextField("Search show on source");
        searchField.setId("monitor-search-field");
        searchField.setPlaceholder("Type show title to search...");
        searchField.setWidthFull();

        // Parse a default title from the directory (replace dots with spaces, strip year)
        String defaultTitle = entry.directoryName().replaceAll("\\.(\\d{4})$", "").replace(".", " ").trim();
        if (existing != null) {
            searchField.setValue(existing.getTitle());
        } else {
            searchField.setValue(defaultTitle);
        }

        // Search results
        ComboBox<ContentMetadata> resultSelect = new ComboBox<>("Matched show");
        resultSelect.setId("monitor-result-select");
        resultSelect.setWidthFull();
        resultSelect.setItemLabelGenerator(c -> c.getTitle()
                + (c.getYear() != null ? " (" + c.getYear() + ")" : "")
                + " — " + c.getSource().getDisplayName());
        resultSelect.setPlaceholder("Run search first");

        Button searchBtn = new Button("Search", VaadinIcon.SEARCH.create());
        searchBtn.setId("monitor-search-button");
        searchBtn.addThemeVariants(ButtonVariant.LUMO_PRIMARY, ButtonVariant.LUMO_SMALL);
        searchBtn.addClickListener(e -> {
            String query = searchField.getValue().trim();
            if (query.isBlank()) return;
            MediaSource chosenSource = sourceSelect.getValue();
            if (chosenSource == null) return;
            try {
                MediaSourceProvider provider = sourceRegistry.get(chosenSource);
                List<ContentMetadata> results = provider.search(query, ContentTypeFilter.TV);
                resultSelect.setItems(results);
                if (!results.isEmpty()) {
                    resultSelect.setValue(results.get(0));
                }
            } catch (Exception ex) {
                log.error("Search error: {}", ex.getMessage());
                showNotification("Search failed: " + ex.getMessage(), true);
            }
        });

        HorizontalLayout searchRow = new HorizontalLayout(searchField, searchBtn);
        searchRow.setWidthFull();
        searchRow.setDefaultVerticalComponentAlignment(FlexComponent.Alignment.END);
        searchRow.expand(searchField);

        content.add(sourceSelect, searchRow, resultSelect);

        // Footer buttons
        Button saveBtn = new Button("Save", VaadinIcon.CHECK.create());
        saveBtn.setId("monitor-save-button");
        saveBtn.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        saveBtn.addClickListener(e -> {
            ContentMetadata selected = resultSelect.getValue();
            if (selected == null) {
                showNotification("Please search and select a show first", true);
                return;
            }
            if (existing == null) {
                monitoringService.addMonitoredShow(
                        selected.getTitle(),
                        selected.getYear(),
                        selected.getTmdbId(),
                        selected.getSource(),
                        selected.getSourceMetadata(),
                        entry.directoryName());
                showNotification("Now monitoring " + selected.getTitle(), false);
            } else {
                monitoringService.updateSourceConfig(
                        existing.getId(),
                        selected.getTitle(),
                        selected.getYear(),
                        selected.getTmdbId(),
                        selected.getSource(),
                        selected.getSourceMetadata());
                showNotification("Updated monitoring config for " + selected.getTitle(), false);
            }
            dialog.close();
            refresh();
        });

        Button cancelBtn = new Button("Cancel");
        cancelBtn.addClickListener(e -> dialog.close());

        dialog.add(content);
        dialog.getFooter().add(cancelBtn, saveBtn);
        dialog.open();
    }

    private void confirmRemove(MonitoredShow show) {
        ConfirmDialog confirm = new ConfirmDialog();
        confirm.setHeader("Remove monitoring");
        confirm.setText("Stop monitoring \"" + show.getTitle() + "\"? Already-downloaded episodes are kept.");
        confirm.setConfirmText("Remove");
        confirm.setConfirmButtonTheme("error primary");
        confirm.setCancelText("Cancel");
        confirm.setCancelable(true);
        confirm.addConfirmListener(e -> {
            monitoringService.removeMonitoredShow(show.getId());
            showNotification("Monitoring removed for " + show.getTitle(), false);
            refresh();
        });
        confirm.open();
    }

    private void refresh() {
        List<LibraryEntry> entries = monitoringService.scanLibrary();
        grid.setItems(entries);
    }

    private void showNotification(String message, boolean error) {
        Notification n = Notification.show(message, 3000, Notification.Position.BOTTOM_END);
        n.addThemeVariants(error ? NotificationVariant.LUMO_ERROR : NotificationVariant.LUMO_SUCCESS);
    }
}
