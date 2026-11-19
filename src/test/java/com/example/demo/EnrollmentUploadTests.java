package com.example.demo;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.util.*;

import com.example.demo.model.domain.*;
import com.example.demo.model.dto.PostForm;
import com.example.demo.model.repository.*;
import com.example.demo.model.service.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.*;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class EnrollmentUploadTests {
    private static final Path UPLOADS = Path.of(System.getProperty("java.io.tmpdir"), "enrollment-test-" + UUID.randomUUID());
    private static final byte[] PNG = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jZ1kAAAAASUVORK5CYII=");

    @DynamicPropertySource
    static void uploadDir(DynamicPropertyRegistry registry) {
        registry.add("app.upload-dir", () -> UPLOADS.toString());
    }

    @Autowired MockMvc mvc;
    @Autowired PostRepository posts;
    @Autowired MemberRepository members;
    @Autowired EnrollmentRepository enrollments;
    @Autowired CommentRepository comments;
    @Autowired EnrollmentService enrollmentService;
    @Autowired PostService postService;
    @Autowired FileStore fileStore;
    @Autowired PasswordEncoder encoder;
    @Autowired PlatformTransactionManager manager;
    private TransactionTemplate tx;
    private Member user;
    private Member other;
    private Post a;
    private Post b;
    private Post normal;
    private MockHttpSession session;
    private CsrfToken csrf;
    private final Set<Long> postIds = new HashSet<>();

    @BeforeEach
    void setup() throws Exception {
        tx = new TransactionTemplate(manager);
        tx.executeWithoutResult(status -> {
            user = member("reg-user", "신청 회원", "USER");
            other = member("reg-other", "다른 회원", "USER");
            Member admin = member("reg-admin", "관리자", "ADMIN");
            a = seedPost("모집 A", 5, admin);
            b = seedPost("모집 B", 1, admin);
            normal = seedPost("일반 글", null, admin);
        });
        var login = mvc.perform(get("/login")).andReturn();
        session = (MockHttpSession) login.getRequest().getSession();
        csrf = (CsrfToken) login.getRequest().getAttribute("_csrf");
    }

    @AfterEach
    void cleanup() throws Exception {
        SecurityContextHolder.clearContext();
        tx.executeWithoutResult(status -> {
            enrollments.deleteAll(enrollments.findAll().stream().filter(e -> postIds.contains(e.getPost().getId())).toList());
            comments.deleteAll(comments.findAll().stream().filter(c -> postIds.contains(c.getPost().getId())).toList());
            posts.deleteAll(posts.findAllById(postIds));
            members.deleteAll(members.findAll().stream().filter(m -> m.getUsername().startsWith("reg-")).toList());
        });
        try (var files = Files.list(UPLOADS)) {
            for (Path file : files.toList()) Files.deleteIfExists(file);
        }
    }

    @Test
    void recruitmentAndImageUploadValidation() throws Exception {
        var result = mvc.perform(withCsrf(multipart("/board/write")
                .file(new MockMultipartFile("poster", "../../poster.PNG", "image/png", PNG)), "reg-admin")
                .param("title", "포스터 모집").param("content", "내용").param("capacity", "5"))
            .andExpect(status().isFound()).andReturn();
        long id = Long.parseLong(result.getResponse().getRedirectedUrl().replace("/board/", ""));
        postIds.add(id);
        Post saved = posts.findById(id).orElseThrow();
        assertThat(saved.getCapacity()).isEqualTo(5);
        assertThat(saved.getRemaining()).isEqualTo(5);
        assertThat(saved.getPosterFile()).matches("[a-f0-9-]{36}\\.png");
        assertThat(Files.exists(UPLOADS.resolve(saved.getPosterFile()))).isTrue();
        mvc.perform(get("/uploads/" + saved.getPosterFile())).andExpect(status().isOk()).andExpect(content().bytes(PNG));
        for (String capacity : List.of("0", "101", "abc")) {
            mvc.perform(withCsrf(post("/board/write"), "reg-admin").param("title", "잘못된 정원")
                    .param("content", "내용").param("capacity", capacity))
                .andExpect(status().isOk()).andExpect(view().name("board/form")).andExpect(model().attributeExists("error"));
        }
        for (String filename : List.of("fake.png", "script.html")) {
            mvc.perform(withCsrf(multipart("/board/write").file(new MockMultipartFile("poster", filename,
                    "image/png", "not an image".getBytes(StandardCharsets.UTF_8))), "reg-admin")
                    .param("title", "가짜 이미지").param("content", "내용"))
                .andExpect(view().name("board/form")).andExpect(model().attributeExists("error"));
        }
    }

    @Test
    void uploadSizeLimitAndRollbackFileCleanup() throws Exception {
        String large = fileStore.save(new MockMultipartFile("poster", "large.png", "image/png", Arrays.copyOf(PNG, 3 * 1024 * 1024)));
        assertThat(Files.size(UPLOADS.resolve(large))).isEqualTo(3 * 1024 * 1024);
        mvc.perform(withCsrf(multipart("/board/write").file(new MockMultipartFile("poster", "too-large.png",
                "image/png", Arrays.copyOf(PNG, 7 * 1024 * 1024))), "reg-admin")
                .param("title", "초과 이미지").param("content", "내용"))
            .andExpect(status().is(413)).andExpect(view().name("error/413"));
        as("reg-admin", "ADMIN");
        PostForm form = new PostForm();
        form.setTitle("롤백될 포스터");
        form.setContent("내용");
        form.setPoster(new MockMultipartFile("poster", "poster.png", "image/png", PNG));
        long count = posts.count();
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            postService.write(form, "reg-admin");
            throw new IllegalStateException("저장 후 실패");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(posts.count()).isEqualTo(count);
        try (var files = Files.list(UPLOADS)) { assertThat(files.toList()).containsExactly(UPLOADS.resolve(large)); }
    }

    @Test
    void cartIsSessionScopedDeduplicatedAndAuthenticated() throws Exception {
        mvc.perform(get("/cart")).andExpect(redirectedUrl("/login"));
        mvc.perform(withCsrf(post("/cart/" + normal.getId()), "reg-user"))
            .andExpect(redirectedUrl("/board/" + normal.getId()));
        for (int i = 0; i < 2; i++) mvc.perform(withCsrf(post("/cart/" + a.getId()), "reg-user"))
            .andExpect(redirectedUrl("/cart"));
        assertThat((Set<?>) session.getAttribute("cart")).isEqualTo(Set.of(a.getId()));
        mvc.perform(get("/cart").session(session).header("Authorization", basic("reg-user")))
            .andExpect(status().isOk()).andExpect(content().string(containsString("모집 A")));
        mvc.perform(get("/cart").header("Authorization", basic("reg-other")))
            .andExpect(model().attribute("cartPosts", List.of()));
        mvc.perform(withCsrf(post("/cart/" + a.getId() + "/remove"), "reg-user"));
        assertThat((Set<?>) session.getAttribute("cart")).isEmpty();
        mvc.perform(post("/cart/confirm").session(session).header("Authorization", basic("reg-user")))
            .andExpect(status().isForbidden());
    }

    @Test
    void successfulConfirmationClearsCartAndRejectsDuplicate() throws Exception {
        session.setAttribute("cart", new LinkedHashSet<>(List.of(a.getId(), b.getId())));
        mvc.perform(withCsrf(post("/cart/confirm"), "reg-user"))
            .andExpect(redirectedUrl("/cart")).andExpect(flash().attribute("message", "2개 모집글 신청이 확정되었습니다."));
        assertThat(enrollments.findByMember_UsernameOrderByIdDesc("reg-user")).hasSize(2);
        assertThat(posts.findById(a.getId()).orElseThrow().getAppliedCount()).isEqualTo(1);
        assertThat((Set<?>) session.getAttribute("cart")).isEmpty();
        session.setAttribute("cart", new LinkedHashSet<>(List.of(a.getId())));
        mvc.perform(withCsrf(post("/cart/confirm"), "reg-user"))
            .andExpect(flash().attribute("error", "이미 신청한 모집글입니다 : 모집 A"));
        assertThat(enrollments.findByMember_UsernameOrderByIdDesc("reg-user")).hasSize(2);
        assertThat((Set<?>) session.getAttribute("cart")).hasSize(1);
    }

    @Test
    void fullSecondPostRollsBackFirstInsertAndSeatUpdate() throws Exception {
        as("reg-other", "USER");
        enrollmentService.confirm("reg-other", List.of(b.getId()));
        session.setAttribute("cart", new LinkedHashSet<>(List.of(a.getId(), b.getId())));
        mvc.perform(withCsrf(post("/cart/confirm"), "reg-user"))
            .andExpect(flash().attribute("error", "정원이 마감되었습니다 : 모집 B"));
        assertThat(enrollments.findByMember_UsernameOrderByIdDesc("reg-user")).isEmpty();
        assertThat(posts.findById(a.getId()).orElseThrow().getAppliedCount()).isZero();
        assertThat(posts.findById(b.getId()).orElseThrow().getAppliedCount()).isEqualTo(1);
        assertThat((Set<?>) session.getAttribute("cart")).hasSize(2);
    }

    @Test
    void deletedNormalAndEmptyCartFailWithoutPartialCommit() throws Exception {
        as("reg-user", "USER");
        for (List<Long> ids : List.of(List.<Long>of(), List.of(a.getId(), 999999L), List.of(a.getId(), normal.getId()))) {
            assertThatThrownBy(() -> enrollmentService.confirm("reg-user", ids)).isInstanceOf(IllegalStateException.class);
            assertThat(posts.findById(a.getId()).orElseThrow().getAppliedCount()).isZero();
            assertThat(enrollments.findByMember_UsernameOrderByIdDesc("reg-user")).isEmpty();
        }
        session.setAttribute("cart", new LinkedHashSet<>(List.of(999999L)));
        mvc.perform(get("/cart").session(session).header("Authorization", basic("reg-user")))
            .andExpect(status().isOk()).andExpect(content().string(containsString("삭제된 모집글 (번호 999999)")));
    }

    @Test
    void cancellationOnlyAllowsOwnerAndReopensFullRecruitment() throws Exception {
        as("reg-user", "USER");
        enrollmentService.confirm("reg-user", List.of(b.getId()));
        Long id = enrollments.findByMember_UsernameOrderByIdDesc("reg-user").getFirst().getId();
        for (String username : List.of("reg-other", "reg-admin")) {
            mvc.perform(withCsrf(post("/cart/enrollments/" + id + "/cancel"), username)).andExpect(status().isForbidden());
        }
        assertThat(posts.findById(b.getId()).orElseThrow().isFull()).isTrue();
        mvc.perform(withCsrf(post("/cart/enrollments/" + id + "/cancel"), "reg-user"))
            .andExpect(flash().attribute("message", "신청이 취소되었습니다."));
        assertThat(enrollments.existsById(id)).isFalse();
        assertThat(posts.findById(b.getId()).orElseThrow().getRemaining()).isEqualTo(1);
        mvc.perform(withCsrf(post("/cart/enrollments/" + id + "/cancel"), "reg-user"))
            .andExpect(status().isNotFound());
    }

    @Test
    void enrollmentPreventsPostAndMemberDeletion() throws Exception {
        as("reg-user", "USER");
        enrollmentService.confirm("reg-user", List.of(a.getId()));
        mvc.perform(withCsrf(post("/board/" + a.getId() + "/delete"), "reg-admin"))
            .andExpect(redirectedUrl("/board/" + a.getId() + "?deleteError"));
        mvc.perform(delete("/api/admin/members/" + user.getId()).header("Authorization", basic("reg-admin")))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("신청 내역이 있는 회원은 삭제할 수 없습니다."));
        mvc.perform(withCsrf(post("/admin/members/" + user.getId() + "/delete"), "reg-admin"))
            .andExpect(flash().attribute("error", "신청 내역이 있는 회원은 삭제할 수 없습니다."));
        assertThat(members.existsById(user.getId())).isTrue();
        assertThat(posts.existsById(a.getId())).isTrue();
    }

    @Test
    void existingPostCanAddReplaceAndKeepPoster() throws Exception {
        String url = "/board/" + a.getId() + "/edit";
        mvc.perform(get(url).header("Authorization", basic("reg-admin")))
            .andExpect(status().isOk()).andExpect(content().string(containsString("id=\"poster\"")));
        mvc.perform(withCsrf(multipart(url).file(new MockMultipartFile("poster", "first.png", "image/png", PNG)), "reg-admin")
                .param("title", "모집 A").param("content", "포스터 추가"))
            .andExpect(status().isFound());
        String first = posts.findById(a.getId()).orElseThrow().getPosterFile();
        assertThat(Files.exists(UPLOADS.resolve(first))).isTrue();
        mvc.perform(withCsrf(post(url), "reg-admin").param("title", "제목 수정").param("content", "파일 유지"))
            .andExpect(status().isFound());
        assertThat(posts.findById(a.getId()).orElseThrow().getPosterFile()).isEqualTo(first);
        mvc.perform(withCsrf(multipart(url).file(new MockMultipartFile("poster", "fake.png", "image/png", new byte[]{1})), "reg-admin")
                .param("title", "제목 수정").param("content", "가짜 파일"))
            .andExpect(view().name("board/form")).andExpect(model().attribute("posterFile", first));
        mvc.perform(withCsrf(multipart(url).file(new MockMultipartFile("poster", "next.png", "image/png", PNG)), "reg-admin")
                .param("title", "모집 A").param("content", "포스터 교체"))
            .andExpect(status().isFound());
        String replacement = posts.findById(a.getId()).orElseThrow().getPosterFile();
        assertThat(replacement).isNotEqualTo(first);
        assertThat(Files.exists(UPLOADS.resolve(first))).isFalse();
        assertThat(Files.exists(UPLOADS.resolve(replacement))).isTrue();
        assertThat(posts.findById(a.getId()).orElseThrow().getCapacity()).isEqualTo(5);
    }

    @Test
    void replacementRollbackKeepsOldPosterAndRemovesNewFile() throws Exception {
        String original = fileStore.save(new MockMultipartFile("poster", "original.png", "image/png", PNG));
        tx.executeWithoutResult(status -> posts.findById(a.getId()).orElseThrow().setPosterFile(original));
        as("reg-admin", "ADMIN");
        PostForm form = new PostForm();
        form.setTitle("교체 롤백"); form.setContent("내용");
        form.setPoster(new MockMultipartFile("poster", "new.png", "image/png", PNG));
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            postService.edit(a.getId(), form);
            throw new IllegalStateException("교체 실패");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(posts.findById(a.getId()).orElseThrow().getPosterFile()).isEqualTo(original);
        try (var files = Files.list(UPLOADS)) { assertThat(files.toList()).containsExactly(UPLOADS.resolve(original)); }
    }

    @Test
    void posterDeletionHappensOnlyAfterPostDeletionCommit() throws Exception {
        String filename = fileStore.save(new MockMultipartFile("poster", "poster.png", "image/png", PNG));
        tx.executeWithoutResult(status -> { Post post = posts.findById(a.getId()).orElseThrow(); post.setPosterFile(filename); });
        as("reg-admin", "ADMIN");
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            postService.delete(a.getId());
            throw new IllegalStateException("삭제 롤백");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(Files.exists(UPLOADS.resolve(filename))).isTrue();
        assertThat(posts.existsById(a.getId())).isTrue();
        postService.delete(a.getId());
        assertThat(Files.exists(UPLOADS.resolve(filename))).isFalse();
    }

    private <B extends AbstractMockHttpServletRequestBuilder<B>> B withCsrf(B request, String username) {
        return request.session(session).header("Authorization", basic(username)).param(csrf.getParameterName(), csrf.getToken());
    }

    private void as(String username, String role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(username, "",
            AuthorityUtils.createAuthorityList("ROLE_" + role)));
    }

    private Member member(String username, String name, String role) {
        Member member = new Member();
        member.setUsername(username); member.setName(name); member.setRole(role); member.setPassword(encoder.encode("reg-test"));
        return members.save(member);
    }

    private Post seedPost(String title, Integer capacity, Member author) {
        Post post = new Post();
        post.setTitle(title); post.setContent("내용"); post.setAuthor(author); post.setCreatedAt(LocalDateTime.now()); post.setCapacity(capacity);
        posts.save(post); postIds.add(post.getId());
        return post;
    }

    private String basic(String username) {
        return "Basic " + Base64.getEncoder().encodeToString((username + ":reg-test").getBytes(StandardCharsets.UTF_8));
    }
}
