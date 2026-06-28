package com.github.stormino.config;

import com.vaadin.flow.spring.security.VaadinWebSecurity;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.builders.WebSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;

@Configuration
@EnableWebSecurity
public class SecurityConfig extends VaadinWebSecurity {

    @Override
    protected void configure(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(auth -> auth
                .requestMatchers(
                        "/api/**",
                        "/actuator/**"
                ).permitAll()
        );
        // Disable CSRF for stateless REST endpoints
        http.csrf(csrf -> csrf.ignoringRequestMatchers("/api/**"));
        super.configure(http);
    }

    @Override
    public void configure(WebSecurity web) throws Exception {
        // Paths added here bypass Spring Security AND Vaadin's index-html interceptor,
        // allowing springdoc's resource handlers to serve the Swagger UI directly.
        web.ignoring().requestMatchers(
                "/v3/api-docs/**",
                "/swagger-ui/**",
                "/swagger-ui.html"
        );
        super.configure(web);
    }
}
