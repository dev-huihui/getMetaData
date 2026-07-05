package com.api.getmetadata.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Jackson 2({@code com.fasterxml.jackson}) 용 ObjectMapper 빈 등록.
 *
 * <p>Spring Boot 4 부터는 기본 JSON 라이브러리가 Jackson 3({@code tools.jackson})로 바뀌어,
 * 자동 구성되는 ObjectMapper 는 Jackson 3 타입이다. 이 프로젝트의 서비스 계층은
 * Jackson 2 API 로 작성돼 있으므로 Jackson 2 용 ObjectMapper 를 별도로 제공한다.</p>
 */
@Configuration
public class JacksonConfig {

    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }
}
