package za.co.sfh.stocklistener.payloads;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Polygon status event — ev: "status".
 * Received for: connected, auth_success, auth_failed, subscribed.
 */
public record StatusMessage(
        @JsonProperty("ev") String ev,
        @JsonProperty("status") String status,
        @JsonProperty("message") String message
) {}
