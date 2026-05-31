package com.github.stormino.view;

import com.github.stormino.config.VixSrcProperties;
import com.github.stormino.model.ContentMetadata;
import com.github.stormino.model.ContentTypeFilter;
import com.github.stormino.model.DownloadTask;
import com.github.stormino.model.MediaSource;
import com.github.stormino.service.DownloadQueueService;
import com.github.stormino.service.source.MediaSourceProvider;
import com.github.stormino.service.source.MediaSourceRegistry;
import com.github.stormino.view.component.SearchResultCard;
import com.vaadin.flow.component.Key;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.radiobutton.RadioButtonGroup;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.theme.lumo.LumoUtility;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@Slf4j
@Route(value = "", layout = MainLayout.class)
@PageTitle("Search | VixSrc Downloader")
public class SearchView extends VerticalLayout {

    private final DownloadQueueService downloadQueueService;
    private final VixSrcProperties properties;
    private final MediaSourceRegistry sourceRegistry;

    private final TextField searchField;
    private final RadioButtonGroup<String> contentTypeGroup;
    private final Button searchButton;
    private final Div resultsContainer;

    public SearchView(DownloadQueueService downloadQueueService,
                      VixSrcProperties properties,
                      MediaSourceRegistry sourceRegistry) {
        this.downloadQueueService = downloadQueueService;
        this.properties = properties;
        this.sourceRegistry = sourceRegistry;

        setSizeFull();
        setPadding(false);
        setSpacing(false);
        getStyle().set("padding", "1rem");

        H2 title = new H2("Search Movies & TV Shows");
        title.addClassNames(LumoUtility.Margin.Top.NONE, LumoUtility.Margin.Bottom.SMALL);

        searchField = new TextField();
        searchField.setId("search-input");
        searchField.setPlaceholder("Search movies and TV shows...");
        searchField.setWidthFull();
        searchField.addKeyPressListener(Key.ENTER, e -> performSearch());
        searchField.getStyle().set("min-width", "200px");

        contentTypeGroup = new RadioButtonGroup<>();
        contentTypeGroup.setId("content-type-filter");
        contentTypeGroup.setLabel("Type");
        contentTypeGroup.setItems("Movies", "TV Shows", "Both");
        contentTypeGroup.setValue("Both");

        searchButton = new Button("Search");
        searchButton.setId("search-button");
        searchButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        searchButton.addClickListener(e -> performSearch());
        searchButton.getStyle().set("margin-top", "auto");

        HorizontalLayout searchLayout = new HorizontalLayout(searchField, contentTypeGroup, searchButton);
        searchLayout.setWidthFull();
        searchLayout.setDefaultVerticalComponentAlignment(Alignment.END);
        searchLayout.setSpacing(true);
        searchLayout.addClassNames(LumoUtility.Gap.SMALL);
        searchLayout.getStyle().set("flex-wrap", "wrap");
        searchLayout.expand(searchField);

        resultsContainer = new Div();
        resultsContainer.setId("search-results");
        resultsContainer.addClassNames(
                LumoUtility.Display.GRID,
                LumoUtility.Gap.SMALL,
                LumoUtility.Padding.Vertical.SMALL
        );
        resultsContainer.getStyle()
                .set("grid-template-columns", "repeat(auto-fill, minmax(min(100%, 320px), 1fr))");

        add(title, searchLayout, resultsContainer);
    }

    private void performSearch() {
        String query = searchField.getValue();
        if (query == null || query.isBlank()) {
            Notification.show("Please enter a search term", 3000, Notification.Position.MIDDLE)
                    .addThemeVariants(NotificationVariant.LUMO_ERROR);
            return;
        }

        resultsContainer.removeAll();
        searchButton.setEnabled(false);
        searchButton.setText("Searching...");

        ContentTypeFilter filter = switch (contentTypeGroup.getValue()) {
            case "Movies"   -> ContentTypeFilter.MOVIES;
            case "TV Shows" -> ContentTypeFilter.TV;
            default         -> ContentTypeFilter.BOTH;
        };
        Set<String> languages = Set.of(properties.getDownload().getDefaultLanguage());
        List<MediaSourceProvider> providers = sourceRegistry.all();

        // Each provider searched in parallel; availability filtering is done inside
        // each provider's own thread so slow HTTP checks don't block each other.
        List<CompletableFuture<List<ContentMetadata>>> futures = providers.stream()
                .map(provider -> CompletableFuture.supplyAsync(() -> {
                    try {
                        List<ContentMetadata> raw = provider.search(query, filter);
                        return raw.parallelStream()
                                .filter(c -> provider.checkAvailability(c, languages).isAvailable())
                                .collect(Collectors.toList());
                    } catch (Exception e) {
                        log.warn("Search failed for source {}: {}", provider.source(), e.getMessage());
                        return List.<ContentMetadata>of();
                    }
                }))
                .collect(Collectors.toList());

        CompletableFuture.runAsync(() -> {
            List<ContentMetadata> movies  = new ArrayList<>();
            List<ContentMetadata> tvShows = new ArrayList<>();

            for (CompletableFuture<List<ContentMetadata>> future : futures) {
                try {
                    for (ContentMetadata c : future.get()) {
                        // TV items carry a non-null numberOfSeasons sentinel.
                        if (c.getNumberOfSeasons() != null) {
                            tvShows.add(c);
                        } else {
                            movies.add(c);
                        }
                    }
                } catch (Exception e) {
                    log.warn("Search future failed: {}", e.getMessage());
                }
            }

            List<ContentMetadata> finalMovies  = movies;
            List<ContentMetadata> finalTvShows = tvShows;

            getUI().ifPresent(ui -> ui.access(() -> {
                finalMovies .forEach(m  -> addResultCard(m,  DownloadTask.ContentType.MOVIE));
                finalTvShows.forEach(tv -> addResultCard(tv, DownloadTask.ContentType.TV));

                if (resultsContainer.getChildren().count() == 0) {
                    Paragraph noResults = new Paragraph("No results found for: " + query);
                    noResults.addClassNames(LumoUtility.TextColor.SECONDARY);
                    resultsContainer.add(noResults);
                }

                searchButton.setEnabled(true);
                searchButton.setText("Search");
            }));
        });
    }

    private void addResultCard(ContentMetadata content, DownloadTask.ContentType type) {
        MediaSource source = content.getSource() != null ? content.getSource() : MediaSource.VIXSRC;
        MediaSourceProvider provider = sourceRegistry.get(source);

        SearchResultCard card = new SearchResultCard(
                content,
                type,
                () -> Set.of(properties.getDownload().getDefaultLanguage()),
                () -> properties.getDownload().getDefaultQuality(),
                provider.supportedLanguages(),
                this::handleDownload
        );
        resultsContainer.add(card);
    }

    private void handleDownload(ContentMetadata content, DownloadTask.ContentType type,
                                Set<Integer> seasons, Set<Integer> episodes,
                                Set<String> languages, String quality) {
        String feedbackMessage = buildQueueMessage(type, seasons, episodes);
        Notification.show(feedbackMessage, 2000, Notification.Position.BOTTOM_END);

        int taskCountBefore = downloadQueueService.getAllTasks().size();

        CompletableFuture.supplyAsync(() ->
                downloadQueueService.addDownload(
                        content, type,
                        seasons.isEmpty() ? null : new ArrayList<>(seasons),
                        episodes.isEmpty() ? null : new ArrayList<>(episodes),
                        List.copyOf(languages), quality)
        ).thenAccept(task -> getUI().ifPresent(ui -> ui.access(() -> {
            int added = downloadQueueService.getAllTasks().size() - taskCountBefore;
            String message = added > 1
                    ? String.format("✓ Added %d episodes to queue", added)
                    : task != null ? "✓ Added to queue: " + task.getDisplayName() : "✓ Added to queue";
            Notification.show(message, 5000, Notification.Position.BOTTOM_END)
                    .addThemeVariants(NotificationVariant.LUMO_SUCCESS);
        }))).exceptionally(e -> {
            getUI().ifPresent(ui -> ui.access(() ->
                    Notification.show("Failed to add download: " + e.getMessage(),
                                    5000, Notification.Position.MIDDLE)
                            .addThemeVariants(NotificationVariant.LUMO_ERROR)));
            return null;
        });
    }

    private static String buildQueueMessage(DownloadTask.ContentType type, Set<Integer> seasons, Set<Integer> episodes) {
        if (type == DownloadTask.ContentType.TV) {
            if (seasons.isEmpty() && episodes.isEmpty()) return "Adding entire show to queue...";
            if (!seasons.isEmpty() && episodes.isEmpty())
                return "Adding season(s) " + seasons + " to queue...";
            if (!seasons.isEmpty())
                return "Adding " + episodes.size() + " episode(s) from season(s) " + seasons + " to queue...";
        }
        return "Adding to queue...";
    }
}
