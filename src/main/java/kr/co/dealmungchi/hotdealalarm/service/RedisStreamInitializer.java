package kr.co.dealmungchi.hotdealalarm.service;

import kr.co.dealmungchi.hotdealalarm.config.StreamConfig;
import lombok.extern.slf4j.Slf4j;

import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.ReactiveStreamOperations;
import org.springframework.stereotype.Service;

import reactor.core.publisher.Mono;

@Slf4j
@Service
public class RedisStreamInitializer {

    private final ReactiveRedisTemplate<String, String> reactiveRedisTemplate;
    private final StreamConfig streamConfig;

    public RedisStreamInitializer(ReactiveRedisTemplate<String, String> reactiveRedisTemplate,
            StreamConfig streamConfig) {
        this.reactiveRedisTemplate = reactiveRedisTemplate;
        this.streamConfig = streamConfig;
    }

    public void init() {
        setupConsumerGroups();
    }

    private void setupConsumerGroups() {
        streamConfig.getNewHotDealsStreamKeys().forEach(streamKey -> {
            createConsumerGroup(streamKey).subscribe(
                    success -> log.info("Consumer group {} creation for stream {}: {}", streamConfig.getConsumerGroup(),
                            streamKey, success ? "success" : "already exists"),
                    error -> log.error("Failed to create consumer group for stream {}", streamKey, error));
        });
    }

    /**
     * Create a consumer group for a stream
     */
    private Mono<Boolean> createConsumerGroup(String streamKey) {
        ReactiveStreamOperations<String, Object, Object> streamOps = reactiveRedisTemplate.opsForStream();

        return streamOps.size(streamKey)
                .defaultIfEmpty(0L)
                .flatMap(ready -> checkAndCreateGroup(streamOps, streamKey))
                .onErrorResume(e -> {
                    log.error("Error creating consumer group for stream {}: {}", streamKey, e.getMessage());
                    return Mono.just(false);
                });
    }

    private Mono<Boolean> checkAndCreateGroup(ReactiveStreamOperations<String, Object, Object> streamOps,
            String streamKey) {
        return streamOps.groups(streamKey).collectList()
                .flatMap(groups -> {
                    boolean exists = groups.stream()
                            .anyMatch(g -> streamConfig.getConsumerGroup().equals(g.groupName()));
                    if (exists)
                        return Mono.just(false);

                    return createGroupWithFallback(streamOps, streamKey);
                });
    }

    private Mono<Boolean> createGroupWithFallback(ReactiveStreamOperations<String, Object, Object> streamOps,
            String streamKey) {
        return streamOps.createGroup(streamKey, ReadOffset.from("$"), streamConfig.getConsumerGroup())
                .thenReturn(true)
                .onErrorResume(e -> {
                    log.warn("Failed to create group from $ offset, trying from 0: {}", e.getMessage());
                    return streamOps.createGroup(streamKey, ReadOffset.from("0"), streamConfig.getConsumerGroup())
                            .thenReturn(true)
                            .onErrorResume(e2 -> {
                                log.error("Failed to create group from 0 offset: {}", e2.getMessage());
                                return Mono.just(false);
                            });
                });
    }
}