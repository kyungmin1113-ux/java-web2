package com.example.demo.model.service;

import java.time.LocalDateTime;
import java.util.List;

import com.example.demo.model.domain.Comment;
import com.example.demo.model.domain.Member;
import com.example.demo.model.domain.Post;
import com.example.demo.model.repository.CommentRepository;
import com.example.demo.model.repository.MemberRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Transactional(readOnly = true)
public class CommentService {
    private final CommentRepository commentRepository;
    private final MemberRepository memberRepository;
    private final PostService postService;

    public CommentService(CommentRepository commentRepository, MemberRepository memberRepository, PostService postService) {
        this.commentRepository = commentRepository;
        this.memberRepository = memberRepository;
        this.postService = postService;
    }

    public List<Comment> findByPost(Post post) {
        return commentRepository.findByPostOrderByIdAsc(post);
    }

    @Transactional
    @PreAuthorize("isAuthenticated()")
    public Comment write(Long postId, String content, String username) {
        if (content == null || content.isBlank() || content.length() > 500) {
            throw new IllegalArgumentException("댓글은 1~500자로 입력하세요.");
        }
        Post post = postService.findById(postId);
        Member author = memberRepository.findByUsername(username)
            .orElseThrow(() -> new IllegalArgumentException("회원이 존재하지 않습니다."));
        Comment comment = new Comment();
        comment.setContent(content.trim());
        comment.setPost(post);
        comment.setAuthor(author);
        comment.setCreatedAt(LocalDateTime.now());
        post.getComments().add(comment);
        return commentRepository.save(comment);
    }

    @Transactional
    @PreAuthorize("isAuthenticated()")
    public void delete(Long postId, Long id) {
        Comment comment = commentRepository.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "댓글이 존재하지 않습니다."));
        if (!comment.getPost().getId().equals(postId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "이 글의 댓글이 아닙니다.");
        }
        var auth = SecurityContextHolder.getContext().getAuthentication();
        boolean admin = auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
        if (!admin && !comment.getAuthor().getUsername().equals(auth.getName())) {
            throw new AccessDeniedException("본인이 쓴 댓글만 삭제할 수 있습니다.");
        }
        comment.getPost().getComments().remove(comment);
        commentRepository.delete(comment);
    }
}
