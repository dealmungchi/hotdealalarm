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

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import okhttp3.OkHttpClient;
import java.time.Instant;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Service
public class DiscordAlarmService implements AlarmService {
    private final WebhookClient webhookClient;
    private final int retryAttempts;
    private final int retryDelayMs;
    private final int rateLimitDelayMs;
    private final int rateLimitBurstSize;
    
    // Rate limiting implementation
    private final Queue<WebhookTask> messageQueue = new ConcurrentLinkedQueue<>();
    private final Semaphore rateLimitSemaphore;
    private final AtomicBoolean processingQueue = new AtomicBoolean(false);
    
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
        this.rateLimitDelayMs = alarmConfig.getDiscordRateLimitDelayMs();
        this.rateLimitBurstSize = alarmConfig.getDiscordRateLimitBurstSize();
        this.rateLimitSemaphore = new Semaphore(rateLimitBurstSize);
        
        log.info("Initialized Discord alarm service with rate limit settings: delay={}ms, burstSize={}", 
                rateLimitDelayMs, rateLimitBurstSize);
    }
    
    @PostConstruct
    public void init() {
        log.info("Starting Discord message queue processor thread");
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
        
        log.debug("Queuing Discord alarm for deal: {}", hotDeal.title());
        
        // Create the embed outside the reactive chain
        WebhookEmbed embed = createEmbed(hotDeal);
        WebhookMessage message = new WebhookMessageBuilder()
            .addEmbeds(embed)
            .build();
        
        // Queue the message for controlled delivery
        WebhookTask task = new WebhookTask(message, hotDeal.title());
        messageQueue.offer(task);
        
        // Start processing the queue if not already in progress
        if (processingQueue.compareAndSet(false, true)) {
            Schedulers.boundedElastic().schedule(this::processMessageQueue);
        }
        
        // Return empty since we've manually queued the execution
        return Mono.empty();
    }
    
    private void processMessageQueue() {
        try {
            while (!messageQueue.isEmpty()) {
                WebhookTask task = messageQueue.peek();
                if (task != null) {
                    // Acquire permit - blocks if we've hit the rate limit
                    rateLimitSemaphore.acquire();
                    
                    // Remove from queue only after we've gotten a permit
                    messageQueue.poll();
                    
                    try {
                        sendWithRetry(task.message, task.title, 0);
                    } catch (Exception e) {
                        log.error("Discord alarm execution failed for '{}': {}", 
                                task.title, e.getMessage(), e);
                    }
                    
                    // Schedule semaphore release after delay to control rate
                    Schedulers.boundedElastic().schedule(() -> {
                        rateLimitSemaphore.release();
                    }, rateLimitDelayMs, TimeUnit.MILLISECONDS);
                }
            }
        } catch (Exception e) {
            log.error("Error in message queue processor: {}", e.getMessage(), e);
        } finally {
            // Allow restart of queue processing if more messages arrive
            processingQueue.set(false);
            
            // If messages were added while we were finishing, restart processing
            if (!messageQueue.isEmpty() && processingQueue.compareAndSet(false, true)) {
                Schedulers.boundedElastic().schedule(this::processMessageQueue);
            }
        }
    }
    
    private void sendWithRetry(WebhookMessage message, String title, int attemptCount) {
        try {
            // This sends synchronously but on a background thread via subscribeOn
            log.debug("Attempting to send Discord message for '{}' (attempt {}/{})", 
                    title, attemptCount + 1, retryAttempts);
                    
            webhookClient.send(message);
            log.debug("Successfully sent Discord message for '{}'", title);
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
                    sendWithRetry(message, title, attemptCount + 1);
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
    
    // Inner class to hold message data in the queue
    private static class WebhookTask {
        final WebhookMessage message;
        final String title;
        
        WebhookTask(WebhookMessage message, String title) {
            this.message = message;
            this.title = title;
        }
    }
}