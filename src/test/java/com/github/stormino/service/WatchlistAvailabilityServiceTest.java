package com.github.stormino.service;

import com.github.stormino.model.AvailabilityResult;
import com.github.stormino.model.ContentMetadata;
import com.github.stormino.model.DownloadTask;
import com.github.stormino.model.MediaSource;
import com.github.stormino.persistence.WatchlistItemRecord;
import com.github.stormino.persistence.WatchlistItemRepository;
import com.github.stormino.service.link.xdcc.XdccResults;
import com.github.stormino.service.link.xdcc.XdccSearch;
import com.github.stormino.service.source.MediaSourceProvider;
import com.github.stormino.service.source.MediaSourceRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WatchlistAvailabilityServiceTest {

    WatchlistItemRepository repository = mock(WatchlistItemRepository.class);
    WatchlistService watchlist = mock(WatchlistService.class);
    MediaSourceProvider vixsrc = mock(MediaSourceProvider.class);
    MediaSourceProvider raiplay = mock(MediaSourceProvider.class);
    XdccSearch xdcc = mock(XdccSearch.class);
    XdccResults grouping = mock(XdccResults.class);
    WatchlistAvailabilityService service;

    /** "Se domani non torno" (2025), saved from the English TMDB title with the Italian one as other name. */
    WatchlistItemRecord film = record("w1", "alice");

    static WatchlistItemRecord record(String id, String user) {
        WatchlistItemRecord r = new WatchlistItemRecord();
        r.setId(id);
        r.setUserName(user);
        r.setTitleKey("MOVIE:123");
        r.setContentType("MOVIE");
        r.setTmdbId(123);
        r.setTitle("If I Don't Come Back");
        r.setYear(2025);
        r.setWaiting(1);
        return r;
    }

    @BeforeEach
    void setUp() {
        when(vixsrc.source()).thenReturn(MediaSource.VIXSRC);
        when(raiplay.source()).thenReturn(MediaSource.RAIPLAY);
        MediaSourceRegistry registry = mock(MediaSourceRegistry.class);
        when(registry.all()).thenReturn(List.of(vixsrc, raiplay));
        when(vixsrc.checkAvailability(any(), any())).thenReturn(AvailabilityResult.builder().available(false).build());
        when(raiplay.search(anyString(), any())).thenReturn(List.of());
        when(xdcc.search(anyString())).thenReturn(new XdccSearch.Result(List.of(), List.of()));
        when(grouping.group(any(), anyString())).thenReturn(List.of());
        when(watchlist.names(any())).thenReturn(List.of("Se domani non torno"));
        service = new WatchlistAvailabilityService(repository, watchlist, registry, provider(xdcc), provider(grouping));
    }

    @SuppressWarnings("unchecked")
    static <T> ObjectProvider<T> provider(T bean) {
        ObjectProvider<T> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(bean);
        return p;
    }

    @Test
    void notFoundAnywhereOnlyRecordsTheCheck() {
        when(repository.findWaiting()).thenReturn(List.of(film));

        assertThat(service.checkAll()).isZero();

        verify(repository).markChecked(eq("w1"), any());
        verify(repository, never()).markAvailable(anyString(), any(), anyString(), anyString());
    }

    @Test
    void vixSrcByTmdbIdSearchesTheSavedTitle() {
        when(vixsrc.checkAvailability(any(), any())).thenReturn(AvailabilityResult.builder().available(true).build());

        WatchlistAvailabilityService.Found f = service.find(film);

        assertThat(f.sources()).containsExactly("VIXSRC");
        assertThat(f.query()).isEqualTo("If I Don't Come Back");
    }

    @Test
    void raiPlayNeedsTheSameNameAndYear() {
        ContentMetadata other = ContentMetadata.builder().source(MediaSource.RAIPLAY)
                .contentType(DownloadTask.ContentType.MOVIE).title("A domani").year(2025).build();
        ContentMetadata oldFilm = ContentMetadata.builder().source(MediaSource.RAIPLAY)
                .contentType(DownloadTask.ContentType.MOVIE).title("Se domani non torno").year(1990).build();
        when(raiplay.search(anyString(), any())).thenReturn(List.of(other, oldFilm));
        assertThat(service.find(film).any()).isFalse();

        ContentMetadata it = ContentMetadata.builder().source(MediaSource.RAIPLAY)
                .contentType(DownloadTask.ContentType.MOVIE).title("Se domani non torno").year(2025).build();
        when(raiplay.search(eq("Se domani non torno"), any())).thenReturn(List.of(other, it));

        WatchlistAvailabilityService.Found f = service.find(film);
        assertThat(f.sources()).containsExactly("RAIPLAY");
        assertThat(f.query()).isEqualTo("Se domani non torno"); // the name that found it
    }

    @Test
    void xdccByTmdbIdOrNameAndYear() {
        XdccResults.Release release = new XdccResults.Release("Se.domani.non.torno.2025.iTA.1080p.mkv", null, "4G",
                null, null, "mkv", 2, List.of(), null, List.of(), true, List.of());
        when(grouping.group(any(), eq("Se domani non torno"))).thenReturn(List.of(new XdccResults.TitleGroup(
                "Se domani non torno", 2025, DownloadTask.ContentType.MOVIE, 3, List.of(release), null, null, null)));

        WatchlistAvailabilityService.Found f = service.find(film);

        assertThat(f.sources()).containsExactly("XDCC");
        assertThat(f.query()).isEqualTo("Se domani non torno");
    }

    @Test
    void aTitleSeveralUsersWaitForIsLookedUpOnce() {
        WatchlistItemRecord bobs = record("w2", "bob");
        when(repository.findWaiting()).thenReturn(List.of(film, bobs));
        when(vixsrc.checkAvailability(any(), any())).thenReturn(AvailabilityResult.builder().available(true).build());

        assertThat(service.checkAll()).isEqualTo(1);

        verify(vixsrc, org.mockito.Mockito.times(1)).checkAvailability(any(), any());
        verify(repository).markAvailable(eq("w1"), any(), eq("VIXSRC"), eq("If I Don't Come Back"));
        verify(repository).markAvailable(eq("w2"), any(), eq("VIXSRC"), eq("If I Don't Come Back"));
    }
}
