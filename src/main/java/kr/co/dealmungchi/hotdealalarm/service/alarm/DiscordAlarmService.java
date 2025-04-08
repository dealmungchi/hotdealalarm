package kr.co.dealmungchi.hotdealalarm.service.alarm;

import club.minnced.discord.webhook.WebhookClient;
import club.minnced.discord.webhook.WebhookClientBuilder;
import club.minnced.discord.webhook.send.WebhookEmbed;
import club.minnced.discord.webhook.send.WebhookEmbedBuilder;
import club.minnced.discord.webhook.send.WebhookMessage;
import club.minnced.discord.webhook.send.WebhookMessageBuilder;
import kr.co.dealmungchi.hotdealalarm.config.AlarmConfig;
import kr.co.dealmungchi.hotdealalarm.domain.model.HotDealDto;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import jakarta.annotation.PreDestroy;
import okhttp3.OkHttpClient;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class DiscordAlarmService implements AlarmService {
    private final WebhookClient webhookClient;
    private final int retryAttempts;
    private final int retryDelayMs;

    public DiscordAlarmService(AlarmConfig alarmConfig) {
        log.info("Initializing Discord alarm service with webhook URL: {}", maskUrl(alarmConfig.getDiscordWebhookUrl()));
        
        // Configure custom OkHttpClient with timeouts
        OkHttpClient httpClient = new OkHttpClient.Builder()
                .connectTimeout(alarmConfig.getDiscordTimeoutMs(), TimeUnit.MILLISECONDS)
                .writeTimeout(alarmConfig.getDiscordTimeoutMs(), TimeUnit.MILLISECONDS)
                .readTimeout(alarmConfig.getDiscordTimeoutMs(), TimeUnit.MILLISECONDS)
                .build();
        
        this.webhookClient = new WebhookClientBuilder(alarmConfig.getDiscordWebhookUrl())
                .setHttpClient(httpClient)
                .setWait(true) // Wait for Discord confirmation
                .build();
        
        this.retryAttempts = alarmConfig.getDiscordRetryAttempts();
        this.retryDelayMs = alarmConfig.getDiscordRetryDelayMs();
    }
    
    @PreDestroy
    public void cleanup() {
        log.info("Closing Discord webhook client");
        if (webhookClient != null) {
            webhookClient.close();
        }
    }

    @Override
    public Mono<Void> sendAlarm(HotDealDto hotDeal) {
        if (hotDeal == null) {
            log.warn("Attempted to send null hotDeal object");
            return Mono.empty();
        }
        
        log.debug("Sending Discord alarm for deal: {}", hotDeal.title());
        
        // Create the embed outside the reactive chain
        WebhookEmbed embed = createEmbed(hotDeal);
        
        // Execute directly without relying on subscriber
        try {
            // Force immediate execution on a background thread
            Schedulers.boundedElastic().schedule(() -> {
                try {
                    sendWithRetry(embed, hotDeal.title(), 0);
                } catch (Exception e) {
                    log.error("Discord alarm execution failed: {}", e.getMessage(), e);
                }
            });
        } catch (Exception e) {
            log.error("Failed to schedule Discord webhook execution: {}", e.getMessage(), e);
        }
        
        // Return empty since we've manually triggered the execution
        return Mono.empty();
    }
    
    private void sendWithRetry(WebhookEmbed embed, String title, int attemptCount) {
        try {
            // Create a more explicit message with the embed
            WebhookMessage message = new WebhookMessageBuilder()
                .addEmbeds(embed)
                .build();
                
            // This sends synchronously but on a background thread via subscribeOn
            log.debug("Attempting to send Discord message for '{}' (attempt {}/{})", 
                    title, attemptCount + 1, retryAttempts);
                    
            webhookClient.send(message);
        } catch (Exception e) {
            log.error("Error sending Discord message (attempt {}/{}): {} - {}", 
                    attemptCount + 1, retryAttempts, e.getClass().getName(), e.getMessage(), e);
            
            // Print the full stack trace in debug mode
            if (log.isDebugEnabled()) {
                for (StackTraceElement element : e.getStackTrace()) {
                    log.debug("  at {}", element);
                }
            }
            
            if (attemptCount < retryAttempts - 1) {
                try {
                    int delayMs = retryDelayMs * (attemptCount + 1); // Exponential backoff
                    log.debug("Retrying Discord message in {}ms (attempt {})", 
                            delayMs, attemptCount + 2);
                    Thread.sleep(delayMs);
                    sendWithRetry(embed, title, attemptCount + 1);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    log.error("Retry was interrupted", ie);
                }
            } else {
                log.error("Failed to send Discord message after {} attempts for deal: {}", 
                        retryAttempts, title, e);
            }
        }
    }

    private WebhookEmbed createEmbed(HotDealDto hotDeal) {
        WebhookEmbedBuilder builder = new WebhookEmbedBuilder()
                .setTitle(new WebhookEmbed.EmbedTitle(hotDeal.title(), hotDeal.link()))
                .setDescription(formatPrice(hotDeal.price()))
                .setTimestamp(Instant.now());

        // Add provider and posted date as footer
        String footerText = "Provider: " + hotDeal.provider();
        if (hotDeal.postedAt() != null) {
            footerText += " | Posted: " + hotDeal.postedAt().toString();
        }
        builder.setFooter(new WebhookEmbed.EmbedFooter(footerText, null));

        // Thumbnail field contains raw data, not a URL - can't be used with Discord webhook
        // Discord requires image URLs, but we have raw image data
        log.debug("Skipping thumbnail for hot deal: {}", hotDeal.title());

        return builder.build();
    }

    private String formatPrice(String price) {
        if (price == null || price.isBlank()) {
            return "Price not available";
        }
        return "Price: " + price;
    }
    
    private String maskUrl(String url) {
        if (url == null || url.length() < 20) {
            return "[hidden]";
        }
        // Show first 10 chars and last 5 chars
        return url.substring(0, 10) + "..." + url.substring(url.length() - 5);
    }
}