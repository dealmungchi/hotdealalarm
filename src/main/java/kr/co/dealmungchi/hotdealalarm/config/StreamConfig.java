package kr.co.dealmungchi.hotdealalarm.config;

import java.util.List;
import java.util.stream.IntStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PostConstruct;

@Configuration
public class StreamConfig {

    private static final Logger log = LoggerFactory.getLogger(StreamConfig.class);

    @Value("${redis.stream.new-hotdeals.key}")
    private String streamNewHotdealsKey;

    @Value("${redis.stream.new-hotdeals.partitions}")
    private int streamNewHotdealsPartitions;

    @Value("${redis.stream.consumer-group}")
    private String consumerGroup;

    @Value("${redis.stream.consumer-name}")
    private String consumerName;

    @PostConstruct
    public void init() {
        log.info("Initializing stream config with key: {}, partitions: {}, group: {}, consumer: {}",
                streamNewHotdealsKey, streamNewHotdealsPartitions, consumerGroup, consumerName);
    }

    public String getStreamNewHotdealsKey() {
        return streamNewHotdealsKey;
    }

    public int getStreamNewHotdealsPartitions() {
        return streamNewHotdealsPartitions;
    }

    public String getConsumerGroup() {
        return consumerGroup;
    }

    public String getConsumerName() {
        return consumerName;
    }

    public List<String> getNewHotDealsStreamKeys() {
        return generatePartitionedKeys(streamNewHotdealsKey, streamNewHotdealsPartitions);
    }

    private List<String> generatePartitionedKeys(String keyPrefix, int numPartitions) {
        return IntStream.range(0, numPartitions)
                .mapToObj(partition -> String.format("%s:%d", keyPrefix, partition))
                .toList();
    }
}