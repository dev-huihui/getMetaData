package com.api.getmetadata.controller;

import com.api.getmetadata.dto.ExtractRequest;
import com.api.getmetadata.service.MetadataService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 메타데이터 추출 REST API.
 *
 * <ul>
 *   <li>POST /api/metadata/mail-header : 메일 헤더 원문(text/plain)에서 추출</li>
 *   <li>POST /api/metadata/url         : URL(JSON {"value": "..."})에서 추출</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/metadata")
public class MetadataController {

    private final MetadataService metadataService;

    public MetadataController(MetadataService metadataService) {
        this.metadataService = metadataService;
    }

    /** 메일 헤더 원문을 그대로 text/plain 본문으로 받아 추출한다. */
    @PostMapping(value = "/mail-header", consumes = MediaType.TEXT_PLAIN_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public JsonNode fromMailHeader(@RequestBody String rawHeader) {
        return metadataService.extractFromMailHeader(rawHeader);
    }

    /** URL 을 JSON 본문 {"value": "https://..."} 으로 받아 추출한다. */
    @PostMapping(value = "/url", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public JsonNode fromUrl(@RequestBody ExtractRequest request) {
        return metadataService.extractFromUrl(request.value());
    }
}
