package com.wiki4ai;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Basic integration test for the Wiki4AI application.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class Wiki4AiApplicationTests {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void contextLoads() {
        // Verify that the Spring context loads successfully
    }

    @Test
    void healthEndpointReturnsUp() {
        ResponseEntity<Map> response = restTemplate.getForEntity("/api/health", Map.class);
        
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("status", "UP");
        assertThat(response.getBody()).containsKey("application");
    }

    @Test
    void actuatorHealthEndpointReturnsUp() {
        ResponseEntity<Map> response = restTemplate.getForEntity("/actuator/health", Map.class);
        
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("status", "UP");
    }
}
