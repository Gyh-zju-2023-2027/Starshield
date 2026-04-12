package com.starshield.backend.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.URI;
import java.util.Arrays;

/**
 * Elasticsearch Java Client 8.x 配置。
 */
@Configuration
public class ElasticsearchClientConfig {

    @Bean(destroyMethod = "close")
    public RestClient elasticsearchRestClient(@Value("${spring.elasticsearch.uris}") String elasticsearchUris) {
        HttpHost[] hosts = Arrays.stream(elasticsearchUris.split(","))
                .map(String::trim)
                .filter(uri -> !uri.isBlank())
                .map(this::toHttpHost)
                .toArray(HttpHost[]::new);
        return RestClient.builder(hosts).build();
    }

    @Bean(destroyMethod = "close")
    public ElasticsearchTransport elasticsearchTransport(RestClient elasticsearchRestClient) {
        return new RestClientTransport(elasticsearchRestClient, new JacksonJsonpMapper());
    }

    @Bean
    public ElasticsearchClient elasticsearchClient(ElasticsearchTransport elasticsearchTransport) {
        return new ElasticsearchClient(elasticsearchTransport);
    }

    private HttpHost toHttpHost(String uri) {
        URI parsed = URI.create(uri);
        int port = parsed.getPort();
        if (port < 0) {
            port = "https".equalsIgnoreCase(parsed.getScheme()) ? 443 : 80;
        }
        return new HttpHost(parsed.getHost(), port, parsed.getScheme());
    }
}