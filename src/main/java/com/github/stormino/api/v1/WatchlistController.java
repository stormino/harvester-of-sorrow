package com.github.stormino.api.v1;

import com.github.stormino.api.ApiException;
import com.github.stormino.api.dto.WatchlistItemDto;
import com.github.stormino.api.dto.WatchlistRequest;
import com.github.stormino.service.WatchlistAvailabilityService;
import com.github.stormino.service.WatchlistService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/watchlist")
@RequiredArgsConstructor
@Tag(name = "Watchlist", description = "Titles saved for later, per user")
public class WatchlistController {

    /** Set by the login proxy (Authelia via Traefik). Without it everyone shares one list. */
    static final String USER_HEADER = "Remote-User";
    static final String DEFAULT_USER = "default";

    private final WatchlistService watchlist;
    private final WatchlistAvailabilityService availability;

    @Operation(summary = "The current user's watchlist, most recently saved first")
    @GetMapping
    public List<WatchlistItemDto> list(@Parameter(hidden = true) @RequestHeader(value = USER_HEADER, required = false) String user) {
        return watchlist.list(userOf(user));
    }

    @Operation(summary = "Save a title for later")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public WatchlistItemDto add(@Parameter(hidden = true) @RequestHeader(value = USER_HEADER, required = false) String user,
                                @Valid @RequestBody WatchlistRequest req) {
        return watchlist.add(userOf(user), req);
    }

    @Operation(summary = "Remove a title from the watchlist")
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@Parameter(hidden = true) @RequestHeader(value = USER_HEADER, required = false) String user,
                       @PathVariable String id) {
        if (!watchlist.remove(userOf(user), id)) {
            throw ApiException.notFound("Watchlist item", id);
        }
    }

    @Operation(summary = "Mark a title that became available as seen",
            description = "Drops its \"now available\" badge; the UI calls it when the user opens the title.")
    @PostMapping("/{id}/seen")
    public WatchlistItemDto seen(@Parameter(hidden = true) @RequestHeader(value = USER_HEADER, required = false) String user,
                                 @PathVariable String id) {
        return watchlist.markSeen(userOf(user), id).orElseThrow(() -> ApiException.notFound("Watchlist item", id));
    }

    @Operation(summary = "Look for the current user's waiting titles now",
            description = "Runs the daily check (VixSrc, RaiPlay, XDCC) for this user's titles no source had when "
                    + "they were saved. Can take a while: XDCC is searched by each name. Returns the watchlist.")
    @PostMapping("/check")
    public List<WatchlistItemDto> check(@Parameter(hidden = true) @RequestHeader(value = USER_HEADER, required = false) String user) {
        availability.checkUser(userOf(user));
        return watchlist.list(userOf(user));
    }

    static String userOf(String header) {
        return header == null || header.isBlank() ? DEFAULT_USER : header.strip();
    }
}
