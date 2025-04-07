package kr.co.dealmungchi.hotdealalarm.service;

import kr.co.dealmungchi.hotdealalarm.domain.model.RedisStreamMessage;
import reactor.core.publisher.Mono;

/**
 * Handler for Redis stream messages.
 * Provides both synchronous and reactive methods for handling messages.
 */
public interface StreamMessageHandler {
    /**
     * Handles a Redis stream message synchronously.
     * This method is primarily for backward compatibility.
     * New implementations should prefer the reactive method.
     *
     * @param message The message to handle
     */
    void handleMessage(RedisStreamMessage message);
    
    /**
     * Handles a Redis stream message reactively.
     * This is the preferred method for new implementations.
     *
     * @param message The message to handle
     * @return A Mono that completes when message handling is finished
     */
    Mono<Void> handleMessageReactive(RedisStreamMessage message);
}