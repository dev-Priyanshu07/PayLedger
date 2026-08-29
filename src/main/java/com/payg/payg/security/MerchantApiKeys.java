package com.payg.payg.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

/**
 * Static per-merchant API keys, as permitted by the spec's scope section.
 *
 * <p>Configured as {@code payg.api-keys[<key>]=<merchantId>}. Bracket notation
 * matters: relaxed binding would otherwise mangle keys containing underscores.
 */
@ConfigurationProperties(prefix = "payg")
public record MerchantApiKeys(Map<String, String> apiKeys) {

    public MerchantApiKeys {
        apiKeys = apiKeys == null ? Map.of() : Map.copyOf(apiKeys);
    }

    /** The merchant owning this key, or null if the key is unknown. */
    public String merchantFor(String apiKey) {
        return apiKey == null ? null : apiKeys.get(apiKey);
    }
}
