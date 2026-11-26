package com.example.demo.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

@Configuration // [12주차 제공] WebSocket + STOMP 설정
@EnableWebSocketMessageBroker // STOMP 메시지 중개(브로커) 기능을 켠다
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        // 브라우저는 구독만 가능하다. 직접 발행하거나 다른 사용자의 실제 큐를 구독할 수 없다.
        registration.interceptors(new ChannelInterceptor() {
            @Override
            public Message<?> preSend(Message<?> message, MessageChannel channel) {
                var headers = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
                if (headers == null) return message;
                if (headers.getCommand() == StompCommand.SEND) {
                    throw new AccessDeniedException("서버만 알림을 발행할 수 있습니다.");
                }
                if (headers.getCommand() == StompCommand.SUBSCRIBE) {
                    String destination = headers.getDestination();
                    boolean seats = "/topic/seats".equals(destination);
                    boolean personal = "/user/queue/notifications".equals(destination) && headers.getUser() != null;
                    if (!seats && !personal) throw new AccessDeniedException("구독할 수 없는 주소입니다.");
                }
                return message;
            }
        });
    }

    @Override // ① 연결 주소 : ws://localhost:8080/ws
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws"); // 허용 출처를 따로 정하지 않으면 "같은 사이트"에서만 연결된다 (setAllowedOrigins("*") 금지)
    }

    @Override // ② 주소 규칙
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic", "/queue");    // 서버 → 브라우저 : /topic = 여러 명, /queue = 한 사람
        registry.setApplicationDestinationPrefixes("/app"); // 브라우저 → 서버 (@MessageMapping, 오늘은 쓰지 않음)
        registry.setUserDestinationPrefix("/user");         // 특정 사용자에게 : /user/queue/...
    }
}
