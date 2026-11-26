package com.example.demo.model.service;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import com.example.demo.model.domain.*;
import com.example.demo.model.repository.*;
import com.example.demo.model.dto.SeatMessage;
import com.example.demo.model.dto.Notification;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Transactional(readOnly = true)
public class EnrollmentService {
    private final EnrollmentRepository enrollmentRepository;
    private final PostRepository postRepository;
    private final MemberRepository memberRepository;
    private final ApplicationEventPublisher eventPublisher;

    public EnrollmentService(EnrollmentRepository enrollmentRepository, PostRepository postRepository,
                             MemberRepository memberRepository, ApplicationEventPublisher eventPublisher) {
        this.enrollmentRepository = enrollmentRepository;
        this.postRepository = postRepository;
        this.memberRepository = memberRepository;
        this.eventPublisher = eventPublisher;
    }

    @PreAuthorize("isAuthenticated()")
    public List<Enrollment> findMine(String username) {
        return enrollmentRepository.findByMember_UsernameOrderByIdDesc(username);
    }

    @Transactional
    @PreAuthorize("isAuthenticated()")
    public int confirm(String username, Collection<Long> postIds) {
        var ids = new LinkedHashSet<>(postIds);
        if (ids.isEmpty()) throw new IllegalStateException("바구니가 비어 있습니다.");
        Member member = memberRepository.findByUsername(username)
            .orElseThrow(() -> new IllegalStateException("회원이 존재하지 않습니다."));
        for (Long id : ids) {
            Post post = postRepository.findById(id)
                .orElseThrow(() -> new IllegalStateException("삭제된 모집글이 바구니에 있습니다."));
            if (!post.isRecruit()) throw new IllegalStateException("모집글이 아닙니다 : " + post.getTitle());
            if (enrollmentRepository.existsByMemberAndPost(member, post)) {
                throw new IllegalStateException("이미 신청한 모집글입니다 : " + post.getTitle());
            }
            if (post.isFull()) throw new IllegalStateException("정원이 마감되었습니다 : " + post.getTitle());
            Enrollment enrollment = new Enrollment();
            enrollment.setMember(member);
            enrollment.setPost(post);
            enrollment.setCreatedAt(LocalDateTime.now());
            enrollmentRepository.save(enrollment);
            post.setAppliedCount(post.getAppliedCount() + 1);
            postRepository.save(post);
            eventPublisher.publishEvent(SeatMessage.from(post));
            String writer = post.getAuthor().getUsername();
            if (!writer.equals(username)) {
                eventPublisher.publishEvent(new Notification(writer,
                    member.getName() + "님이 '" + post.getTitle() + "' 에 신청했습니다. ("
                    + post.getAppliedCount() + "/" + post.getCapacity() + ")", post.getId()));
            }
        }
        return ids.size();
    }

    @Transactional
    @PreAuthorize("isAuthenticated()")
    public void cancel(Long id) {
        Enrollment enrollment = enrollmentRepository.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "신청 내역이 존재하지 않습니다."));
        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        if (!enrollment.getMember().getUsername().equals(username)) {
            throw new AccessDeniedException("본인의 신청만 취소할 수 있습니다.");
        }
        Post post = enrollment.getPost();
        if (post.getAppliedCount() <= 0) throw new IllegalStateException("신청 인원을 확인할 수 없습니다.");
        enrollmentRepository.delete(enrollment);
        post.setAppliedCount(post.getAppliedCount() - 1);
        eventPublisher.publishEvent(SeatMessage.from(post));
        String writer = post.getAuthor().getUsername();
        if (!writer.equals(username)) {
            eventPublisher.publishEvent(new Notification(writer,
                enrollment.getMember().getName() + "님이 '" + post.getTitle()
                + "' 신청을 취소했습니다.", post.getId()));
        }
    }
}
