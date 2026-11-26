package com.example.demo;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import com.example.demo.model.domain.*;
import com.example.demo.model.dto.*;
import com.example.demo.model.repository.*;
import com.example.demo.model.service.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.messaging.*;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.stomp.*;
import org.springframework.messaging.support.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
class RealtimeTests {
    @Autowired EnrollmentService service;
    @Autowired CommentService commentService;
    @Autowired PostRepository posts;
    @Autowired MemberRepository members;
    @Autowired EnrollmentRepository enrollments;
    @Autowired CommentRepository comments;
    @Autowired PlatformTransactionManager manager;
    @Autowired ApplicationEventPublisher events;
    @Autowired @Qualifier("brokerChannel") AbstractSubscribableChannel broker;
    @Autowired @Qualifier("clientInboundChannel") AbstractSubscribableChannel inbound;
    final List<Message<?>> sent = new CopyOnWriteArrayList<>();
    final ChannelInterceptor recorder = new ChannelInterceptor() {
        public Message<?> preSend(Message<?> message, MessageChannel channel) {
            if (SimpMessageHeaderAccessor.getDestination(message.getHeaders()) != null) sent.add(message);
            return message;
        }
    };
    TransactionTemplate tx;
    Post a, b;
    Member writer, applicant;

    @BeforeEach void setup() {
        tx = new TransactionTemplate(manager);
        tx.executeWithoutResult(status -> {
            writer = member("rt-writer"); applicant = member("rt-applicant");
            a = post("실시간 A", 5); b = post("실시간 B", 1);
        });
        login(applicant.getUsername());
        broker.addInterceptor(recorder);
    }

    @AfterEach void cleanup() {
        broker.removeInterceptor(recorder);
        SecurityContextHolder.clearContext();
        tx.executeWithoutResult(status -> {
            enrollments.deleteAll(); comments.deleteAll(); posts.deleteAll(); members.deleteAll();
        });
    }

    @Test void committedApplicationSendsSeatsAndOnlyWriterNotification() {
        tx.executeWithoutResult(status -> {
            service.confirm(applicant.getUsername(), List.of(a.getId()));
            assertThat(sent).isEmpty(); // 아직 커밋하지 않았으므로 전송하지 않는다.
        });
        assertThat(destinations()).containsExactly("/topic/seats", "/user/rt-writer/queue/notifications");
        assertThat(body(0)).contains("\"remaining\":4", "\"appliedCount\":1");
        assertThat(body(1)).contains("신청했습니다", "rt-applicant");
    }

    @Test void rollbackSendsNeitherSeatsNorNotification() {
        tx.executeWithoutResult(status -> { var p = posts.findById(b.getId()).orElseThrow(); p.setAppliedCount(1); });
        assertThatThrownBy(() -> service.confirm(applicant.getUsername(), List.of(a.getId(), b.getId())))
            .isInstanceOf(IllegalStateException.class);
        assertThat(sent).isEmpty();
        assertThat(posts.findById(a.getId()).orElseThrow().getAppliedCount()).isZero();
        assertThat(enrollments.count()).isZero();
    }

    @Test void cancellationReopensFullPostAndNotifiesWriter() {
        service.confirm(applicant.getUsername(), List.of(b.getId()));
        Long id = enrollments.findAll().getFirst().getId(); sent.clear();
        service.cancel(id);
        assertThat(destinations()).containsExactly("/topic/seats", "/user/rt-writer/queue/notifications");
        assertThat(body(0)).contains("\"remaining\":1", "\"appliedCount\":0");
        assertThat(body(1)).contains("취소했습니다");
        assertThat(posts.findById(b.getId()).orElseThrow().isFull()).isFalse();
    }

    @Test void ownApplicationCancellationAndCommentDoNotNotifySelf() {
        login(writer.getUsername());
        service.confirm(writer.getUsername(), List.of(a.getId()));
        service.cancel(enrollments.findAll().getFirst().getId());
        commentService.write(a.getId(), "내 댓글", writer.getUsername());
        assertThat(destinations()).containsExactly("/topic/seats", "/topic/seats");
    }

    @Test void commentNotifiesAfterCommitButNotAfterRollback() {
        tx.executeWithoutResult(status -> {
            commentService.write(a.getId(), "댓글", applicant.getUsername());
            assertThat(sent).isEmpty();
        });
        assertThat(destinations()).containsExactly("/user/rt-writer/queue/notifications");
        assertThat(body(0)).contains("댓글을 남겼습니다"); sent.clear();
        tx.executeWithoutResult(status -> {
            commentService.write(a.getId(), "롤백 댓글", applicant.getUsername()); status.setRollbackOnly();
        });
        assertThat(sent).isEmpty();
    }

    @Test void eventWithoutTransactionDoesNotSend() {
        events.publishEvent(SeatMessage.from(a));
        events.publishEvent(new Notification(writer.getUsername(), "전송되면 안 됨", a.getId()));
        assertThat(sent).isEmpty();
    }

    @Test void clientCannotForgeBroadcastOrSubscribeToPrivateBrokerQueue() {
        assertThatThrownBy(() -> inbound.send(frame(StompCommand.SEND, "/topic/seats", true)))
            .isInstanceOf(MessageDeliveryException.class);
        assertThatThrownBy(() -> inbound.send(frame(StompCommand.SUBSCRIBE, "/queue/notifications-user-other", true)))
            .isInstanceOf(MessageDeliveryException.class);
        assertThatThrownBy(() -> inbound.send(frame(StompCommand.SUBSCRIBE, "/user/queue/notifications", false)))
            .isInstanceOf(MessageDeliveryException.class);
    }

    private Message<byte[]> frame(StompCommand command, String destination, boolean authenticated) {
        var h = StompHeaderAccessor.create(command); h.setDestination(destination); h.setSessionId("test");
        h.setSubscriptionId("test-sub");
        if (authenticated) h.setUser(SecurityContextHolder.getContext().getAuthentication());
        return MessageBuilder.createMessage(new byte[0], h.getMessageHeaders());
    }
    private List<String> destinations() { return sent.stream().map(m -> SimpMessageHeaderAccessor.getDestination(m.getHeaders())).toList(); }
    private String body(int i) { return new String((byte[]) sent.get(i).getPayload(), java.nio.charset.StandardCharsets.UTF_8); }
    private void login(String name) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(name, "unused", AuthorityUtils.createAuthorityList("ROLE_USER")));
    }
    private Member member(String username) {
        Member m = new Member(); m.setUsername(username); m.setPassword("unused"); m.setName(username); m.setRole("USER"); return members.save(m);
    }
    private Post post(String title, int capacity) {
        Post p = new Post(); p.setTitle(title); p.setContent("테스트 내용"); p.setAuthor(writer); p.setCapacity(capacity); p.setCreatedAt(LocalDateTime.now()); return posts.save(p);
    }
}
