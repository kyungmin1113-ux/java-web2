package com.example.demo.model.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Collection;
import org.springframework.web.multipart.MultipartFile;
import com.example.demo.model.repository.EnrollmentRepository;

import com.example.demo.model.domain.Member;
import com.example.demo.model.domain.Post;
import com.example.demo.model.dto.PostForm;
import com.example.demo.model.repository.MemberRepository;
import com.example.demo.model.repository.PostRepository;
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

@Service
@Transactional(readOnly = true)
public class PostService {

    public static final int PAGE_SIZE = 10;

    private final PostRepository postRepository;
    private final MemberRepository memberRepository;
    private final EnrollmentRepository enrollmentRepository;
    private final FileStore fileStore;

    public PostService(PostRepository postRepository, MemberRepository memberRepository,
                       EnrollmentRepository enrollmentRepository, FileStore fileStore) {
        this.postRepository = postRepository;
        this.memberRepository = memberRepository;
        this.enrollmentRepository = enrollmentRepository;
        this.fileStore = fileStore;
    }

    public List<Post> findAll() {
        return postRepository.findAll(Sort.by(Sort.Direction.DESC, "id"));
    }

    public List<Post> findAllById(Collection<Long> ids) {
        return postRepository.findAllById(ids);
    }

    public Page<Post> findPage(String keyword, String type, int page) {
        PageRequest pageable = PageRequest.of(Math.max(page, 0), PAGE_SIZE, Sort.by(Sort.Direction.DESC, "id"));
        if (keyword == null || keyword.isBlank()) {
            return postRepository.findAll(pageable);
        }
        return "writer".equals(type)
            ? postRepository.searchByWriter(keyword.trim(), pageable)
            : postRepository.search(keyword.trim(), pageable);
    }

    @PreAuthorize("isAuthenticated()")
    public Page<Post> findMyPage(String username, int page) {
        return postRepository.findByAuthor_Username(username,
            PageRequest.of(Math.max(page, 0), PAGE_SIZE, Sort.by(Sort.Direction.DESC, "id")));
    }

    @PreAuthorize("isAuthenticated()")
    public List<Post> findMyPosts(String username) {
        return postRepository.findByAuthor_UsernameOrderByIdDesc(username);
    }

    public Post findById(Long id) {
        return postRepository.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "글이 존재하지 않습니다."));
    }

    @Transactional
    public Post read(Long id) {
        if (postRepository.incrementViewCount(id) == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "글이 존재하지 않습니다.");
        }
        return findById(id);
    }

    @Transactional
    @PreAuthorize("isAuthenticated()")
    public Post write(PostForm form, String username) {
        validate(form);
        Member author = memberRepository.findByUsername(username)
            .orElseThrow(() -> new IllegalArgumentException("회원이 존재하지 않습니다."));
        Post post = new Post();
        post.setTitle(form.getTitle().trim());
        post.setContent(form.getContent());
        post.setAuthor(author);
        post.setCreatedAt(LocalDateTime.now());
        post.setCapacity(form.getCapacity());
        post.setPosterFile(savePoster(form.getPoster()));
        return postRepository.save(post);
    }

    @PreAuthorize("isAuthenticated()")
    public Post findForEdit(Long id) {
        Post post = findById(id);
        checkWriter(post);
        return post;
    }

    @Transactional
    @PreAuthorize("isAuthenticated()")
    public Post edit(Long id, PostForm form) {
        Post post = findForEdit(id);
        validate(form);
        String poster = savePoster(form.getPoster());
        if (poster != null) {
            deletePosterAfterCommit(post.getPosterFile());
            post.setPosterFile(poster);
        }
        post.setTitle(form.getTitle().trim());
        post.setContent(form.getContent());
        post.setUpdatedAt(LocalDateTime.now());
        return postRepository.save(post);
    }

    @Transactional
    @PreAuthorize("isAuthenticated()")
    public void delete(Long id) {
        Post post = findById(id);
        if (!isAdmin()) {
            checkWriter(post);
        }
        if (enrollmentRepository.existsByPost(post)) {
            throw new IllegalStateException("신청자가 있는 모집글은 삭제할 수 없습니다.");
        }
        String poster = post.getPosterFile();
        postRepository.delete(post);
        deletePosterAfterCommit(poster);
    }

    private String savePoster(MultipartFile file) {
        String poster = fileStore.save(file);
        if (poster != null) {
            // 파일은 DB 롤백에 포함되지 않으므로 저장 실패 시 직접 정리한다.
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    if (status != STATUS_COMMITTED) fileStore.delete(poster);
                }
            });
        }
        return poster;
    }

    private void deletePosterAfterCommit(String poster) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                fileStore.delete(poster);
            }
        });
    }

    private void validate(PostForm form) {
        if (form.getCapacity() != null && (form.getCapacity() < 1 || form.getCapacity() > 100)) {
            throw new IllegalArgumentException("모집 인원은 1~100명으로 입력하세요.");
        }
        if (form.getTitle() == null || form.getTitle().isBlank()
            || form.getContent() == null || form.getContent().isBlank()) {
            throw new IllegalArgumentException("제목과 내용을 모두 입력하세요.");
        }
        if (form.getTitle().trim().length() > 100) {
            throw new IllegalArgumentException("제목은 100자까지 입력할 수 있습니다.");
        }
    }

    private Authentication currentAuthentication() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) {
            throw new AccessDeniedException("로그인이 필요합니다.");
        }
        return auth;
    }

    private void checkWriter(Post post) {
        if (!post.getAuthor().getUsername().equals(currentAuthentication().getName())) {
            throw new AccessDeniedException("본인이 쓴 글만 수정·삭제할 수 있습니다.");
        }
    }

    private boolean isAdmin() {
        return currentAuthentication().getAuthorities().stream()
            .anyMatch(authority -> authority.getAuthority().equals("ROLE_ADMIN"));
    }
}
