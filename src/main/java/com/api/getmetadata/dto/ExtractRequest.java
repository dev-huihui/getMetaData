package com.api.getmetadata.dto;

/**
 * 메타데이터 추출 요청 본문.
 *
 * @param value 추출 대상 원본 문자열 (메일 헤더 전문 또는 URL)
 */
public record ExtractRequest(String value) {
}
