package com.connecthub.websocket.interceptor;

import java.security.Principal; /**
     * StompPrincipal — lightweight session identity for authenticated WebSocket users.
     *
     * Carries the userId (used as getName() for user-destination routing), the display
     * username, and the subscription tier extracted from JWT claims. ChatWebSocketHandler
     * casts the session principal to StompPrincipal to read the tier without re-parsing
     * the token on every message.
     *
     * The two-argument constructor defaults subscriptionTier to "FREE" for compatibility
     * with places that create a principal without explicit tier info.
     */
    public record StompPrincipal(String id, String username, String subscriptionTier) implements Principal {
        public StompPrincipal(String id, String username) {
            this(id, username, "FREE");
        }
        @Override public String getName() { return id; }
    }
