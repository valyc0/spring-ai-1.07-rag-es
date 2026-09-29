package com.example.demo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Logga la request HTTP completa di ogni chiamata REST fatta da Spring AI (chat + embedding).
 * Disattivabile con app.http-log.enabled=false.
 */
@Configuration
public class HttpLoggingConfig {

    private static final Logger log = LoggerFactory.getLogger("http.request");

    private static final List<String> SENSITIVE_HEADERS = List.of("authorization", "api-key", "x-api-key");

    @Bean
    RestClientCustomizer loggingRestClientCustomizer(@Value("${app.http-log.enabled:true}") boolean enabled) {
        return builder -> {
            if (enabled) {
                builder.requestInterceptor(new LoggingInterceptor());
            }
        };
    }

    private static final class LoggingInterceptor implements ClientHttpRequestInterceptor {

        @Override
        public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
                throws IOException {

            if (log.isInfoEnabled()) {
                log.info("--> {} {}", request.getMethod(), request.getURI());
                request.getHeaders().forEach((name, values) -> log.info("--> {}: {}", name,
                        SENSITIVE_HEADERS.contains(name.toLowerCase()) ? mask(values) : String.join(", ", values)));
                log.info("--> body ({} bytes): {}", body.length, new String(body, StandardCharsets.UTF_8));
            }

            ClientHttpResponse response = execution.execute(request, body);

            if (log.isInfoEnabled()) {
                log.info("<-- {} {}", response.getStatusCode(), request.getURI());
            }
            return response;
        }

        private static String mask(List<String> values) {
            return "***(" + values.size() + " value, " + values.get(0).length() + " chars)";
        }
    }
}
