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

    /** 메일 헤더 원문에서 메타데이터 추출. 검증된 원본 JSON 문자열을 반환한다. */
    public String extractFromMailHeader(String rawHeader) {
        return extract("mail_header", rawHeader);
    }

    /** URL 에서 메타데이터 추출. 검증된 원본 JSON 문자열을 반환한다. */
    public String extractFromUrl(String url) {
        return extract("url", url);
    }

    /**
     * Python 추출기를 실행하고, 응답 JSON 의 성공 여부만 검증한 뒤
     * 원본 JSON 문자열을 그대로 반환한다.
     *
     * <p>파싱한 트리를 그대로 반환하지 않고 원본 문자열을 돌려주는 이유는,
     * Spring Boot 4 의 기본 HTTP 컨버터가 Jackson 3 이라 Jackson 2 의 {@code JsonNode}
     * 를 트리로 직렬화하지 못하기 때문이다. 문자열로 반환하면 라이브러리에 무관하게
     * Python 이 만든 JSON 이 그대로 클라이언트에 전달된다.</p>
     */
    private String extract(String type, String value) {
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
            return responseJson;
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
