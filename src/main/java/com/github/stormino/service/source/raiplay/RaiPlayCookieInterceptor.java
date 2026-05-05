package com.github.stormino.service.source.raiplay;

import com.github.stormino.config.RaiPlayProperties;
import lombok.RequiredArgsConstructor;
import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;

/**
 * Injects the configured RaiPlay session cookie into any request whose host
 * lives under {@code rai.it}. This covers raiplay.it, mediapolisvod.rai.it
 * (the relinker host) and any other RAI hosts the playback chain redirects to.
 *
 * <p>If no cookie is configured, the interceptor is a no-op.
 */
@RequiredArgsConstructor
public class RaiPlayCookieInterceptor implements Interceptor {

    private final RaiPlayProperties properties;

    @NotNull
    @Override
    public Response intercept(@NotNull Chain chain) throws IOException {
        Request request = chain.request();
        String cookie = properties.getSessionCookie();
        if (cookie == null || cookie.isBlank()) {
            return chain.proceed(request);
        }
        String host = request.url().host();
        if (!host.endsWith(".rai.it") && !host.equals("rai.it")) {
            return chain.proceed(request);
        }
        if (request.header("Cookie") != null) {
            return chain.proceed(request);
        }
        Request authed = request.newBuilder()
                .addHeader("Cookie", cookie)
                .build();
        return chain.proceed(authed);
    }
}
