package com.cixingji.backend.service.impl.videosummary.client;

import com.cixingji.backend.config.videosummary.VideoSummaryProperties;
import com.cixingji.backend.service.impl.videosummary.media.AudioArtifact;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.time.Instant;

@Component
public class XfyunAsrClient implements AsrClient {

    private static final String SUCCESS_CODE = "000000";

    private final VideoSummaryProperties properties;
    private final ObjectMapper objectMapper;
    private final XfyunTranscriptParser transcriptParser;
    private final RestTemplate restTemplate;

    public XfyunAsrClient(VideoSummaryProperties properties,
                          ObjectMapper objectMapper,
                          XfyunTranscriptParser transcriptParser) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.transcriptParser = transcriptParser;
        this.restTemplate = createRestTemplate(properties.getXfyun());
    }

    @Override
    public String submit(AudioArtifact audio, String callbackUrl) {
        requireCredentials();
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        VideoSummaryProperties.Xfyun config = properties.getXfyun();
        UriComponentsBuilder builder = signedUri(config.getUploadUrl(), timestamp)
                .queryParam("fileName", audio.getPath().getFileName().toString())
                .queryParam("fileSize", audio.getPath().toFile().length())
                .queryParam("duration", Math.max(audio.getDurationMillis(), 1L))
                .queryParam("language", config.getLanguage())
                .queryParam("audioMode", "fileStream");
        if (StringUtils.hasText(callbackUrl)) {
            builder.queryParam("callbackUrl", callbackUrl);
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        HttpEntity<FileSystemResource> request = new HttpEntity<>(
                new FileSystemResource(audio.getPath().toFile()), headers);
        ResponseEntity<String> response = restTemplate.exchange(
                builder.build().encode().toUri(), HttpMethod.POST, request, String.class);

        JsonNode root = readJson(response.getBody());
        assertSuccess(root, "submit transcription");
        String orderId = root.path("content").path("orderId").asText();
        if (!StringUtils.hasText(orderId)) {
            throw new IllegalStateException("XFYun did not return an orderId");
        }
        return orderId;
    }

    @Override
    public AsrQueryResult query(String orderId) {
        requireCredentials();
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        URI uri = signedUri(properties.getXfyun().getResultUrl(), timestamp)
                .queryParam("orderId", orderId)
                .build().encode().toUri();

        ResponseEntity<String> response = restTemplate.exchange(uri, HttpMethod.GET, HttpEntity.EMPTY, String.class);
        JsonNode root = readJson(response.getBody());
        assertSuccess(root, "query transcription");

        JsonNode content = root.path("content");
        JsonNode orderInfo = content.path("orderInfo");
        int status = orderInfo.path("status").asInt(Integer.MIN_VALUE);
        if (status == 0 || status == 3) {
            return AsrQueryResult.processing();
        }
        if (status == -1) {
            return AsrQueryResult.failed("XFYun transcription failed, failType="
                    + orderInfo.path("failType").asInt(-1));
        }
        if (status != 4) {
            return AsrQueryResult.failed("Unknown XFYun transcription status: " + status);
        }

        String orderResult = content.path("orderResult").asText();
        if (!StringUtils.hasText(orderResult)) {
            return AsrQueryResult.failed("XFYun completed without an orderResult");
        }
        return AsrQueryResult.completed(transcriptParser.parse(orderResult));
    }

    private UriComponentsBuilder signedUri(String baseUrl, String timestamp) {
        VideoSummaryProperties.Xfyun config = properties.getXfyun();
        String signature = XfyunSignature.create(config.getAppId(), timestamp, config.getSecretKey());
        return UriComponentsBuilder.fromHttpUrl(baseUrl)
                .queryParam("appId", config.getAppId())
                .queryParam("ts", timestamp)
                .queryParam("signa", signature);
    }

    private void requireCredentials() {
        if (!StringUtils.hasText(properties.getXfyun().getAppId())
                || !StringUtils.hasText(properties.getXfyun().getSecretKey())) {
            throw new IllegalStateException("XFYun ASR credentials are not configured");
        }
    }

    private JsonNode readJson(String body) {
        try {
            return objectMapper.readTree(body == null ? "" : body);
        } catch (Exception e) {
            throw new IllegalStateException("XFYun returned an invalid JSON response", e);
        }
    }

    private void assertSuccess(JsonNode root, String action) {
        String code = root.path("code").asText();
        if (!SUCCESS_CODE.equals(code)) {
            throw new IllegalStateException("Could not " + action + ": code=" + code
                    + ", message=" + root.path("descInfo").asText("unknown"));
        }
    }

    private RestTemplate createRestTemplate(VideoSummaryProperties.Xfyun config) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(config.getConnectTimeoutMs());
        factory.setReadTimeout(config.getReadTimeoutMs());
        factory.setBufferRequestBody(false);
        return new RestTemplate(factory);
    }
}
