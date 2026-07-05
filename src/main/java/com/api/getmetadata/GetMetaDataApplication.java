package com.api.getmetadata;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class GetMetaDataApplication {

    public static void main(String[] args) {
        SpringApplication.run(GetMetaDataApplication.class, args);
    }

}
