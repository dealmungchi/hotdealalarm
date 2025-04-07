package kr.co.dealmungchi.hotdealalarm.domain.model;

import lombok.Builder;
import lombok.Getter;

/**
 * Represents a message from a Redis stream.
 * Contains the message ID, stream key, provider, and encoded data.
 */
@Getter
@Builder
public class RedisStreamMessage {
    /**
     * The unique ID of the message in the Redis stream
     */
    private final String messageId;
    
    /**
     * The key of the Redis stream this message was read from
     */
    private final String streamKey;
    
    /**
     * The provider identifier (key in the Redis stream entry)
     */
    private final String provider;
    
    /**
     * The encoded data (value in the Redis stream entry)
     * This is typically Base64-encoded JSON
     */
    private final String data;
}
