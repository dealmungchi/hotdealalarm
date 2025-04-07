package kr.co.dealmungchi.hotdealalarm.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import kr.co.dealmungchi.hotdealalarm.domain.model.HotDealDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Decodes Base64-encoded messages containing HotDealDto data in JSON format.
 * Supports both single object and array formats for backward compatibility.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class Base64MessageDecoder {
    
    private final ObjectMapper objectMapper;
    
    /**
     * Decodes a Base64-encoded string into a stream of HotDealDto objects.
     *
     * @param encodedData The Base64-encoded JSON data
     * @return A Flux of HotDealDto objects
     */
    public Flux<HotDealDto> decode(String encodedData) {
        if (encodedData == null || encodedData.isBlank()) {
            log.warn("Received null or empty encoded data");
            return Flux.empty();
        }
        
        log.debug("Decoding message data with length: {}", encodedData.length());
        return Mono.fromCallable(() -> decodeBase64(encodedData))
                .flatMapMany(json -> parseSingleOrArray(json, encodedData))
                .doOnNext(dto -> log.debug("Decoded hot deal: {} from {}", dto.title(), dto.provider()))
                .onErrorResume(e -> {
                    log.error("Error processing encoded data (length: {}): {} - {}", 
                            encodedData.length(), e.getClass().getName(), e.getMessage(), e);
                    return Flux.empty();
                });
    }
    
    /**
     * Decodes a Base64-encoded string to its original string content.
     *
     * @param encodedData The Base64-encoded data
     * @return The decoded string
     * @throws IllegalArgumentException If the input is not valid Base64
     */
    private String decodeBase64(String encodedData) {
        try {
            return new String(Base64.getDecoder().decode(encodedData), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            log.error("Failed to decode base64 message: {}", encodedData, e);
            throw e;
        }
    }
    
    /**
     * Attempts to parse JSON as either a single HotDealDto or an array.
     *
     * @param json The JSON string to parse
     * @param originalData The original encoded data (for logging purposes)
     * @return A Flux of HotDealDto objects
     */
    private Flux<HotDealDto> parseSingleOrArray(String json, String originalData) {
        // Try parsing as a single object first
        try {
            HotDealDto dto = objectMapper.readValue(json, HotDealDto.class);
            return Flux.just(dto);
        } catch (JsonProcessingException singleItemError) {
            // If that fails, try as an array
            try {
                return Flux.fromArray(objectMapper.readValue(json, HotDealDto[].class));
            } catch (JsonProcessingException arrayError) {
                log.error("Failed to parse JSON as object or array after decoding: {}", originalData, arrayError);
                return Flux.empty();
            }
        }
    }
}
