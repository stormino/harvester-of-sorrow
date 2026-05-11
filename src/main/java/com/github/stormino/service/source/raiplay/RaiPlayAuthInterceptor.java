package com.github.stormino.service.source.raiplay;

import lombok.RequiredArgsConstructor;
import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;

/**
 * Injects RaiPlay's RaiSSO authentication headers ({@code x-auth-token} and
 * {@code domainapikey}) plus the {@code x-caller} markers into any request
 * whose host lives under {@code rai.it}. This covers raiplay.it,
 * mediapolisvod.rai.it (the relinker / HLS host), and any other RAI host the
 * playback chain redirects to.
 *
 * <p>The token + key pair is obtained from {@link RaiPlayAuthService}, which
 * auto-refreshes via the RaiSSO endpoints on a schedule. If the service is
 * unavailable or login failed, the interceptor is a no-op.
 */
@RequiredArgsConstructor
public class RaiPlayAuthInterceptor implements Interceptor {

    private final RaiPlayAuthService authService;

    @NotNull
    @Override
    public Response intercept(@NotNull Chain chain) throws IOException {
        Request request = chain.request();
        String host = request.url().host();
        if (!host.endsWith(".rai.it") && !host.equals("rai.it")) {
            return chain.proceed(request);
        }

        String token = authService.getAuthToken();
        String dak   = authService.getDomainApiKey();
        if (token == null || token.isBlank() || dak == null || dak.isBlank()) {
            return chain.proceed(request);
        }

        Request.Builder builder = request.newBuilder()
                .header("x-caller", "web")
                .header("x-caller-version", "1.0");
        if (request.header("x-auth-token") == null) {
            builder.header("x-auth-token", token);
        }
        if (request.header("domainapikey") == null) {
            builder.header("domainapikey", dak);
        }
        return chain.proceed(builder.build());
    }
}
