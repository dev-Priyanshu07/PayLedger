package com.payg.payg.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.AllArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Resolves {@code X-API-Key} to a merchant id and exposes it as a request
 * attribute.
 *
 * <p>Merchant identity is established here and nowhere else. It is deliberately
 * absent from every request body: a merchant that could name its own
 * {@code merchantId} could create orders and read payments belonging to
 * another.
 */
@Component
@AllArgsConstructor
public class ApiKeyFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-API-Key";
    public static final String MERCHANT_ID_ATTRIBUTE = "payg.merchantId";

    private final MerchantApiKeys apiKeys;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/v1/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        String merchantId = apiKeys.merchantFor(request.getHeader(HEADER));

        if (merchantId == null) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("""
                    {"code":"unauthorized","message":"Missing or invalid X-API-Key header."}""");
            return;
        }

        request.setAttribute(MERCHANT_ID_ATTRIBUTE, merchantId);
        chain.doFilter(request, response);
    }
}
