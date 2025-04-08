# Hot Deal Alarm Service

A reactive microservice that consumes hot deal messages from Redis streams and sends notifications to Discord via webhooks.

<img src="https://github.com/user-attachments/assets/1179787e-303f-4dd8-9f59-b2871b205c65" width="600" height="700" />

## Features

- Reactive processing of messages using Spring WebFlux and Redis Reactive
- Support for multiple Redis stream partitions
- Discord webhook notifications

## Configuration

All configuration is done via environment variables using a `.env` file:

```
# Redis Configuration
REDIS_HOST=localhost
REDIS_PORT=6379

# Discord Webhook URL
DISCORD_WEBHOOK_URL=https://discord.com/api/webhooks/your-webhook-id/your-webhook-token

# Redis Stream Configuration
REDIS_STREAM_NEW_HOTDEALS_KEY=streamNewHotdeals
REDIS_STREAM_NEW_HOTDEALS_PARTITIONS=1
REDIS_STREAM_CONSUMER_GROUP=hoteals-alarm-group
```

## Redis Stream Format

Messages in the Redis stream have the following format:

```
<stream-id>
<provider>
base64(<hot-deal-json>)
```

Where `<hot-deal-json>` is a JSON object with the following structure:

```json
{
  "title": "Product Title",
  "link": "https://example.com/product",
  "price": "10,000원",
  "thumbnail": "<thumbnail-data>",
  "thumbnail_link": "<thumbnail-url>",
  "id": "12345",
  "posted_at": "YY/MM/DD",
  "provider": "Provider Name"
}
```

## Building and Running

### Local Development

```bash
# Build the project
./gradlew build

# Run the application
./gradlew bootRun
```

### Using Docker

```bash
# Build and start using docker-compose
docker-compose up --build
```

## License

MIT License
