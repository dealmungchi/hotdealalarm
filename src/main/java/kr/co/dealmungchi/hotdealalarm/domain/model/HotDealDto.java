package kr.co.dealmungchi.hotdealalarm.domain.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public record HotDealDto(
    String title,
    String link,
    String price,
    String thumbnail,
    
    @JsonProperty("thumbnail_link")
    String thumbnailLink,
    
    @JsonProperty("id")
    String postId,
    
    @JsonProperty("posted_at")
    String postedAt,
    
    String provider
) {}
