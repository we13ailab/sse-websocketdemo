package com.we13ailab.ssedemo;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final WsTickHandler wsTickHandler;

    public WebSocketConfig(WsTickHandler wsTickHandler) {
        this.wsTickHandler = wsTickHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // WebSocket endpoint at ws://host:port/ws
        registry.addHandler(wsTickHandler, "/ws").setAllowedOrigins("*");
    }
}
