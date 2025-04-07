package kr.co.dealmungchi.hotdealalarm.service;

import kr.co.dealmungchi.hotdealalarm.config.StreamConfig;
import kr.co.dealmungchi.hotdealalarm.domain.model.RedisStreamMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.ReactiveStreamOperations;
import org.springframework.stereotype.Service;

import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;

@Slf4j
@RequiredArgsConstructor
@Service
public class ReactiveRedisStreamConsumer {

    private final RedisStreamInitializer redisStreamInitializer;

    private final ReactiveRedisTemplate<String, String> reactiveRedisTemplate;
    private final StreamConfig streamConfig;
    private String consumerId;
    private final StreamMessageHandler messageHandler;

    private final Map<String, Disposable> streamSubscriptions = new ConcurrentHashMap<>();

    @EventListener(ApplicationReadyEvent.class)
    public void startConsuming() {
        redisStreamInitializer.init();

        consumerId = streamConfig.getConsumerName();
        streamConfig.getNewHotDealsStreamKeys().forEach(streamKey -> {
            Disposable subscription = startStreamConsumption(streamKey);
            streamSubscriptions.put(streamKey, subscription);
        });
    }

    @SuppressWarnings("unchecked")
    private Disposable startStreamConsumption(String streamKey) {
        ReactiveStreamOperations<String, Object, Object> streamOps = reactiveRedisTemplate.opsForStream();
        Consumer consumer = Consumer.from(streamConfig.getConsumerGroup(), consumerId);

        StreamReadOptions readOptions = StreamReadOptions.empty()
                .block(Duration.ofSeconds(0))
                .count(1)
                .autoAcknowledge();

        return Flux.defer(() -> streamOps.read(consumer, readOptions,
                StreamOffset.create(streamKey, ReadOffset.lastConsumed())))
                .repeat()
                .flatMap(record -> processStreamRecord(streamKey, record))
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe();
    }

    private Mono<Boolean> processStreamRecord(String streamKey, MapRecord<String, Object, Object> record) {
        String messageId = record.getId().getValue();
                
        return processRedisRecord(streamKey, record)
                .doOnSuccess(success -> {
                    if (success) {
                        log.debug("Successfully processed message {} from stream {}", messageId, streamKey);
                    }
                });
    }

    @Value("${redis.stream.message-processing-timeout:15000}")
    private int messageProcessingTimeoutMs;

    private Mono<Boolean> processRedisRecord(String streamKey, MapRecord<String, Object, Object> record) {
        String messageId = record.getId().getValue();
        Map<Object, Object> entries = record.getValue();

        log.debug("Processing message {} from stream {}", messageId, streamKey);
        RedisStreamMessage message = createMessage(streamKey, messageId, entries);

        return messageHandler.handleMessageReactive(message)
                .timeout(Duration.ofMillis(messageProcessingTimeoutMs))
                .then(Mono.just(true))
                .doOnSuccess(success -> log.debug("Successfully processed message {} from stream {}", messageId, streamKey))
                .doOnError(e -> {
                    if (e instanceof TimeoutException) {
                        log.error("Message processing TIMED OUT for message {} from stream {} after {}ms", 
                                messageId, streamKey, messageProcessingTimeoutMs, e);
                    } else {
                        log.error("Failed to process message {} from stream {}: {} - {}", 
                                messageId, streamKey, e.getClass().getName(), e.getMessage(), e);
                    }
                })
                .doFinally(signal -> log.debug("Finished processing message {} from stream {} with signal: {}", messageId, streamKey, signal))
                .onErrorResume(e -> handleProcessingError(e, messageId, streamKey));
    }

    private RedisStreamMessage createMessage(String streamKey, String messageId, Map<Object, Object> entries) {
        Map.Entry<Object, Object> entry = entries.entrySet().iterator().next();
        String provider = entry.getKey().toString();
        String data = entry.getValue().toString();

        return RedisStreamMessage.builder()
                .messageId(messageId)
                .streamKey(streamKey)
                .provider(provider)
                .data(data)
                .build();
    }

    private Mono<Boolean> handleProcessingError(Throwable e, String messageId, String streamKey) {
        if (e instanceof TimeoutException) {
            log.warn("Timeout during processing of message {} from stream {}", messageId, streamKey);
        } else {
            log.error("Error type: {} during processing of message {} from stream {}", 
                    e.getClass().getName(), messageId, streamKey, e);
        }
        
        // Always return false for error case - this allows the flow to continue
        return Mono.just(false);
    }
}