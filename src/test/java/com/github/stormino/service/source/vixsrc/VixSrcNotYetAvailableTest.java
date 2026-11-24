package com.github.stormino.service.source.vixsrc;

import com.github.stormino.model.ContentMetadata;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/** Which TMDB matches VixSrc doesn't serve the search still shows, to save them to the watchlist. */
class VixSrcNotYetAvailableTest {

    static final LocalDate TODAY = LocalDate.of(2026, 11, 24);

    static ContentMetadata film(String title, String localTitle, LocalDate released) {
        return ContentMetadata.builder().title(title).localTitle(localTitle).releaseDate(released).build();
    }

    @Test
    void recentOrUpcomingOnly() {
        assertThat(VixSrcSourceProvider.recent(film("Se domani non torno", null, LocalDate.of(2026, 11, 5)), TODAY)).isTrue();
        assertThat(VixSrcSourceProvider.recent(film("Dune: Part Three", null, LocalDate.of(2026, 12, 15)), TODAY)).isTrue();
        assertThat(VixSrcSourceProvider.recent(film("Dune", null, LocalDate.of(1989, 4, 19)), TODAY)).isFalse();
        assertThat(VixSrcSourceProvider.recent(film("Il Silenzio di Montalbano", null, null), TODAY)).isFalse();
    }

    @Test
    void anyOfItsNamesHasEveryWordOfTheQuery() {
        ContentMetadata f = film("If I Don't Come Back", "Se domani non torno", null);
        assertThat(VixSrcSourceProvider.matchesQuery(f, "se domani non torno")).isTrue();
        assertThat(VixSrcSourceProvider.matchesQuery(f, "if i don't come")).isTrue();
        assertThat(VixSrcSourceProvider.matchesQuery(f, "domani torno a casa")).isFalse();
    }
}
