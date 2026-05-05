package com.github.stormino.service.source.raiplay;

import lombok.RequiredArgsConstructor;
import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;

/**
 * Injects the active RaiPlay session cookie into any request whose host lives
 * under {@code rai.it}. This covers raiplay.it, mediapolisvod.rai.it (the
 * relinker / HLS host), and any other RAI host the playback chain redirects to.
 *
 * <p>The cookie is obtained from {@link RaiPlayAuthService}, which auto-refreshes
 * it via Gigya when username + password are configured.  If auto-login is not
 * configured or fails, the service falls back to the manually pasted
 * {@code raiplay.session-cookie} property.
 *
 * <p>If no cookie is available from either source, the interceptor is a no-op.
 */
@RequiredArgsConstructor
public class RaiPlayCookieInterceptor implements Interceptor {

    private final RaiPlayAuthService authService;

    @NotNull
    @Override
    public Response intercept(@NotNull Chain chain) throws IOException {
        Request request = chain.request();
        String host = request.url().host();
        if (!host.endsWith(".rai.it") && !host.equals("rai.it")) {
            return chain.proceed(request);
        }
        if (request.header("Cookie") != null) {
            // Caller already set a Cookie header; don't override.
            return chain.proceed(request);
        }
        String cookie = authService.getCookie();
        if (cookie == null || cookie.isBlank()) {
            return chain.proceed(request);
        }
        Request authed = request.newBuilder()
                .addHeader("Cookie", cookie)
                .build();
        return chain.proceed(authed);
    }
}
