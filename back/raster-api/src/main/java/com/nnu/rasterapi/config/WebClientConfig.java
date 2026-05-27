package com.nnu.rasterapi.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;

@Configuration
public class WebClientConfig {

    @Bean
    public WebClient agentWebClient(
            WebClient.Builder builder,
            @Value("${agent.base-url:http://127.0.0.1:8001}") String agentBaseUrl,
            @Value("${agent.http-timeout-ms:30000}") long httpTimeoutMs
    ) {
        String base = agentBaseUrl == null ? "http://127.0.0.1:8001" : agentBaseUrl.replaceAll("/$", "");
        Duration timeout = Duration.ofMillis(Math.max(3000, httpTimeoutMs));
        HttpClient httpClient = HttpClient.create().responseTimeout(timeout);
        return builder
                .baseUrl(base)
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }
}
