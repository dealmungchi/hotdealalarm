package kr.co.dealmungchi.hotdealalarm.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Getter
@Configuration
public class AlarmConfig {

    @Value("${alarm.discord.webhook-url:}")
    private String discordWebhookUrl;
    
    @Value("${alarm.discord.retry-attempts:3}")
    private int discordRetryAttempts;
    
    @Value("${alarm.discord.retry-delay-ms:1000}")
    private int discordRetryDelayMs;
    
    @Value("${alarm.discord.timeout-ms:5000}")
    private int discordTimeoutMs;
    
    @PostConstruct
    public void init() {
        log.info("Initialized alarm config with retry attempts: {}, retry delay: {}ms, timeout: {}ms", 
                discordRetryAttempts, discordRetryDelayMs, discordTimeoutMs);
        
        // Validate webhook URL
        if (discordWebhookUrl == null || discordWebhookUrl.isBlank() || !discordWebhookUrl.startsWith("https://discord.com/api/webhooks/")) {
            log.warn("Invalid Discord webhook URL format. Alerts may not be delivered. URL: {}", maskUrl(discordWebhookUrl));
        }
    }
    
    private String maskUrl(String url) {
        if (url == null || url.length() < 20) {
            return "[hidden or empty]"; 
        }
        return url.substring(0, 10) + "..." + url.substring(url.length() - 5);
    }
}
