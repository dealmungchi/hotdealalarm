package kr.co.dealmungchi.hotdealalarm.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import kr.co.dealmungchi.hotdealalarm.domain.model.RedisStreamMessage;
import kr.co.dealmungchi.hotdealalarm.service.alarm.AlarmService;
import reactor.core.publisher.Mono;

/**
 * Handles Redis stream messages containing hot deal data.
 * Processes incoming messages by decoding them and forwarding to the reactive processor.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HotDealStreamMessageHandler implements StreamMessageHandler {
    
    private final Base64MessageDecoder decoder;
    private final AlarmService alarmService;
    
    /**
     * Synchronous message handler implementation.
     * Only used for backward compatibility with tests.
     * 
     * @param message The Redis stream message to process
     */
    @Override
    public void handleMessage(RedisStreamMessage message) {
        // For backward compatibility with tests, delegate to reactive implementation
        handleMessageReactive(message).block();
    }
    
    /**
     * Reactive message handler implementation.
     * Decodes the message data and forwards hot deals to the processor.
     * 
     * @param message The Redis stream message to process
     * @return A Mono that completes when message processing is finished
     */
    @Override
    public Mono<Void> handleMessageReactive(RedisStreamMessage message) {
        String messageId = message.getMessageId();
        
        return decoder.decode(message.getData())
            // Critical fix: Wait for each alarm to be processed before moving to the next
            // This ensures each sendAlarm operation is subscribed to and executed
            .flatMap(dto -> {
                log.info("Processing hot deal: {} from message {}", dto.title(), messageId);
                return alarmService.sendAlarm(dto)
                    .doOnSuccess(v -> log.info("Successfully sent alarm for: {}", dto.title()))
                    .doOnError(e -> log.error("Failed to send alarm for: {}", dto.title(), e))
                    .onErrorResume(e -> Mono.empty()); // Continue with next item even if one fails
            })
            .collectList()
            .flatMap(dtos -> {
                int size = dtos.size();
                if (size == 0) {
                    log.warn("No DTOs found in message {}", messageId);
                    return Mono.empty();
                }
                
                log.debug("Processed {} DTOs from message {}", size, messageId);
                return Mono.empty();
            });
    }
}