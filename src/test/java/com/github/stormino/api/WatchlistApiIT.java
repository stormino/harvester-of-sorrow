package com.github.stormino.api;

import com.github.stormino.api.dto.WatchlistItemDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

class WatchlistApiIT extends AbstractApiIT {

    static final String PERFECT_DAYS = """
            {"titleKey":"MOVIE:976893","contentType":"MOVIE","tmdbId":976893,"title":"Perfect Days","year":2023,
             "overview":"Hirayama is content with his life.","rating":7.8,"posterUrl":"https://image.tmdb.org/p.jpg",
             "externalUrl":"https://www.themoviedb.org/movie/976893",
             "streams":[{"source":"VIXSRC","contentType":"MOVIE","title":"Perfect Days","year":2023,"tmdbId":976893,
                         "seasons":[],"sourceRef":"{\\"type\\":\\"VIXSRC\\",\\"tmdbId\\":976893}"}]}""";

    @BeforeEach
    void clean() {
        jdbc.execute("DELETE FROM watchlist_item");
    }

    static HttpEntity<String> as(String user, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (user != null) headers.set("Remote-User", user);
        return new HttpEntity<>(body, headers);
    }

    WatchlistItemDto[] list(String user) {
        return rest.exchange("/api/v1/watchlist", HttpMethod.GET, as(user, null), WatchlistItemDto[].class).getBody();
    }

    @Test
    void savesListsAndRemovesPerUser() {
        ResponseEntity<WatchlistItemDto> added = rest.exchange("/api/v1/watchlist", HttpMethod.POST,
                as("alice", PERFECT_DAYS), WatchlistItemDto.class);
        assertThat(added.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        WatchlistItemDto item = added.getBody();
        assertThat(item.title()).isEqualTo("Perfect Days");
        assertThat(item.streams()).hasSize(1);
        assertThat(item.streams().get(0).sourceRef()).isEqualTo("{\"type\":\"VIXSRC\",\"tmdbId\":976893}");

        // Saving again refreshes the same entry; empty fields keep what was saved
        WatchlistItemDto again = rest.exchange("/api/v1/watchlist", HttpMethod.POST, as("alice",
                "{\"titleKey\":\"MOVIE:976893\",\"contentType\":\"MOVIE\",\"title\":\"Perfect Days\",\"rating\":8.0}"),
                WatchlistItemDto.class).getBody();
        assertThat(again.id()).isEqualTo(item.id());
        assertThat(again.createdAt()).isEqualTo(item.createdAt());
        assertThat(again.rating()).isEqualTo(8.0);
        assertThat(again.overview()).isEqualTo("Hirayama is content with his life.");
        assertThat(again.streams()).hasSize(1);
        assertThat(list("alice")).hasSize(1);

        // Each user has their own list; without the login header, the shared "default" one
        assertThat(list("bob")).isEmpty();
        assertThat(list(null)).isEmpty();
        assertThat(rest.exchange("/api/v1/watchlist/" + item.id(), HttpMethod.DELETE, as("bob", null), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        assertThat(rest.exchange("/api/v1/watchlist/" + item.id(), HttpMethod.DELETE, as("alice", null), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(list("alice")).isEmpty();
    }

    @Test
    void rejectsAnEntryWithoutTitle() {
        ResponseEntity<String> r = rest.exchange("/api/v1/watchlist", HttpMethod.POST,
                as("alice", "{\"titleKey\":\"MOVIE:1\",\"contentType\":\"MOVIE\"}"), String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
