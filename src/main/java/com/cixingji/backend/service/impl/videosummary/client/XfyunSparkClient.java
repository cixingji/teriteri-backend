package com.cixingji.backend.service.impl.videosummary.client;

import com.cixingji.backend.config.videosummary.VideoSummaryProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class XfyunSparkClient implements TextGenerationClient {

    private final VideoSummaryProperties properties;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;

    public XfyunSparkClient(VideoSummaryProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.restTemplate = createRestTemplate(properties.getSpark());
    }

    @Override
    public String complete(String systemPrompt, String userPrompt) {
        VideoSummaryProperties.Spark config = properties.getSpark();
        if (!StringUtils.hasText(config.getApiPassword())) {
            throw new IllegalStateException("Spark API password is not configured");
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", config.getModel());
        body.put("messages", messages(systemPrompt, userPrompt));
        body.put("temperature", config.getTemperature());
        body.put("max_tokens", config.getMaxTokens());
        body.put("stream", false);
        body.put("response_format", singletonMap("type", "json_object"));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(config.getApiPassword());
        ResponseEntity<String> response = restTemplate.exchange(
                config.getEndpoint(),
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                String.class
        );

        JsonNode root = readJson(response.getBody());
        if (root.has("error")) {
            throw new IllegalStateException("Spark request failed: "
                    + root.path("error").path("message").asText("unknown error"));
        }
        if (root.path("code").asInt(0) != 0) {
            throw new IllegalStateException("Spark request failed: code=" + root.path("code").asInt()
                    + ", message=" + root.path("message").asText("unknown error"));
        }
        String content = root.path("choices").path(0).path("message").path("content").asText();
        if (!StringUtils.hasText(content)) {
            throw new IllegalStateException("Spark returned an empty completion");
        }
        return content;
    }

    private List<Map<String, String>> messages(String systemPrompt, String userPrompt) {
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(stringMap("role", "system", "content", systemPrompt));
        messages.add(stringMap("role", "user", "content", userPrompt));
        return messages;
    }

    private Map<String, Object> singletonMap(String key, Object value) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put(key, value);
        return map;
    }

    private Map<String, String> stringMap(String... values) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) {
            map.put(values[index], values[index + 1]);
        }
        return map;
    }

    private JsonNode readJson(String body) {
        try {
            return objectMapper.readTree(body == null ? "" : body);
        } catch (Exception e) {
            throw new IllegalStateException("Spark returned an invalid JSON response", e);
        }
    }

    private RestTemplate createRestTemplate(VideoSummaryProperties.Spark config) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(config.getConnectTimeoutMs());
        factory.setReadTimeout(config.getReadTimeoutMs());
        return new RestTemplate(factory);
    }
}
