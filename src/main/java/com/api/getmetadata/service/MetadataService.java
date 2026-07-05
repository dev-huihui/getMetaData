package com.api.getmetadata.service;

import com.api.getmetadata.python.PythonExecutionException;
import com.api.getmetadata.python.PythonScriptRunner;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 입력(메일 헤더 / URL)을 Python 추출기로 전달하고 결과 JSON 을 파싱해 반환하는 서비스.
 */
@Service
public class MetadataService {

    private final PythonScriptRunner runner;
    private final ObjectMapper objectMapper;

    public MetadataService(PythonScriptRunner runner, ObjectMapper objectMapper) {
        this.runner = runner;
        this.objectMapper = objectMapper;
    }

    /** 메일 헤더 원문에서 메타데이터 추출. */
    public JsonNode extractFromMailHeader(String rawHeader) {
        return extract("mail_header", rawHeader);
    }

    /** URL 에서 메타데이터 추출. */
    public JsonNode extractFromUrl(String url) {
        return extract("url", url);
    }

    private JsonNode extract(String type, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("입력 값(value)이 비어 있습니다.");
        }

        String requestJson = buildRequest(type, value);
        String responseJson = runner.run(requestJson);

        if (responseJson == null || responseJson.isBlank()) {
            throw new PythonExecutionException("Python 스크립트가 빈 응답을 반환했습니다.");
        }

        try {
            JsonNode result = objectMapper.readTree(responseJson);
            if (!result.path("success").asBoolean(false)) {
                String error = result.path("error").asText("알 수 없는 오류");
                throw new PythonExecutionException("추출 실패: " + error);
            }
            return result;
        } catch (JsonProcessingException e) {
            throw new PythonExecutionException(
                    "Python 응답 JSON 파싱 실패: " + responseJson, e);
        }
    }

    private String buildRequest(String type, String value) {
        try {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("type", type);
            node.put("value", value);
            return objectMapper.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            // ObjectNode 직렬화에서는 사실상 발생하지 않음
            throw new PythonExecutionException("요청 JSON 생성 실패", e);
        }
    }
}
