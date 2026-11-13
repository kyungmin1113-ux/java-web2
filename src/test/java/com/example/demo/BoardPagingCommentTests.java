package com.example.demo;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;

import com.example.demo.model.domain.*;
import com.example.demo.model.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.domain.Page;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class BoardPagingCommentTests {
    @Autowired MockMvc mvc;
    @Autowired PostRepository posts;
    @Autowired CommentRepository comments;
    @Autowired MemberRepository members;
    @Autowired PasswordEncoder encoder;
    private Member writer;
    private Member other;
    private Post first;
    private Post second;

    @BeforeEach
    void setup() {
        writer = member("paging-writer", "기경민", "USER");
        other = member("paging-other", "홍길동", "USER");
        member("paging-admin", "관리자", "ADMIN");
        for (int n = 1; n <= 105; n++) {
            Post post = new Post();
            post.setTitle("테스트 글 " + n);
            post.setContent(n % 8 == 0 ? "스프링 스터디 모집" : "페이징 확인");
            post.setCreatedAt(LocalDateTime.now());
            post.setAuthor(n <= 55 ? writer : other);
            posts.save(post);
            if (n == 1) first = post;
            if (n == 2) second = post;
        }
        posts.flush();
    }

    @Test
    void paginationHandlesFirstLastNegativeAndOutOfRange() throws Exception {
        Page<Post> firstPage = page(mvc.perform(get("/board")).andExpect(status().isOk()).andReturn());
        assertThat(firstPage.getTotalElements()).isEqualTo(105);
        assertThat(firstPage.getTotalPages()).isEqualTo(11);
        assertThat(firstPage.getContent()).hasSize(10);
        assertThat(firstPage.getContent().getFirst().getTitle()).isEqualTo("테스트 글 105");
        Page<Post> next = page(mvc.perform(get("/board").param("page", "1")).andReturn());
        assertThat(next.getContent().getFirst().getTitle()).isEqualTo("테스트 글 95");
        assertThat(page(mvc.perform(get("/board").param("page", "10")).andReturn()).getContent()).hasSize(5);
        assertThat(page(mvc.perform(get("/board").param("page", "-1")).andReturn()).getNumber()).isZero();
        assertThat(page(mvc.perform(get("/board").param("page", "999")).andExpect(status().isOk()).andReturn()).isEmpty()).isTrue();
    }

    @Test
    void searchCoversTitleContentEmptyAndInjectionAndKeepsLinks() throws Exception {
        var response = mvc.perform(get("/board").param("keyword", " 스터디 "))
            .andExpect(status().isOk()).andExpect(model().attribute("keyword", "스터디")).andReturn();
        assertThat(page(response).getTotalElements()).isEqualTo(13);
        assertThat(page(response).getTotalPages()).isEqualTo(2);
        assertThat(page(mvc.perform(get("/board").param("keyword", "스터디").param("page", "1")).andReturn()).getContent()).hasSize(3);
        assertThat(page(mvc.perform(get("/board").param("keyword", "테스트 글 105")).andReturn()).getTotalElements()).isEqualTo(1);
        for (String keyword : List.of("파이썬", "' OR 1=1 --")) {
            mvc.perform(get("/board").param("keyword", keyword))
                .andExpect(status().isOk()).andExpect(content().string(containsString("검색 결과가 없습니다.")));
        }
        assertThat(page(mvc.perform(get("/board").param("keyword", "  ")).andReturn()).getTotalElements()).isEqualTo(105);
        assertThat(response.getResponse().getContentAsString()).contains("page=1", "keyword=", "type=all");
    }

    @Test
    void writerSearchAndMyPostsPagingKeepTheirScope() throws Exception {
        var found = mvc.perform(get("/board").param("type", "writer").param("keyword", "홍길"))
            .andExpect(model().attribute("type", "writer")).andReturn();
        assertThat(page(found).getTotalElements()).isEqualTo(50);
        assertThat(page(found).getContent()).allMatch(p -> p.getAuthor().getUsername().equals("paging-other"));
        assertThat(found.getResponse().getContentAsString()).contains("type=writer", "page=1");
        var mine = mvc.perform(get("/board/my").header("Authorization", basic("paging-writer")))
            .andExpect(model().attribute("listTitle", "내가 쓴 글"))
            .andExpect(model().attribute("pageUrl", "/board/my")).andReturn();
        assertThat(page(mine).getTotalElements()).isEqualTo(55);
        assertThat(mine.getResponse().getContentAsString()).contains("/board/my?page=1");
        assertThat(page(mvc.perform(get("/board/my").param("page", "5")
            .header("Authorization", basic("paging-writer"))).andReturn()).getContent()).hasSize(5);
        mvc.perform(get("/board/my")).andExpect(redirectedUrl("/login"));
    }

    @Test
    void commentWriteUsesCurrentAccountAndPrgDoesNotDuplicate() throws Exception {
        String url = "/board/" + first.getId();
        mvc.perform(withCsrf(post(url + "/comments")).header("Authorization", basic("paging-other"))
                .param("content", "  <script>alert(1)</script>  ").param("author", "paging-admin"))
            .andExpect(redirectedUrl(url + "#comments"));
        var saved = comments.findByPostOrderByIdAsc(first);
        assertThat(saved).hasSize(1);
        assertThat(saved.getFirst().getAuthor().getUsername()).isEqualTo("paging-other");
        assertThat(saved.getFirst().getContent()).isEqualTo("<script>alert(1)</script>");
        for (int n = 0; n < 2; n++) {
            mvc.perform(get(url)).andExpect(status().isOk())
                .andExpect(content().string(containsString("&lt;script&gt;")))
                .andExpect(content().string(containsString("하면 댓글을 쓸 수 있습니다.")));
        }
        assertThat(comments.count()).isEqualTo(1);
        mvc.perform(get("/board").param("type", "all").param("keyword", "테스트 글 1").param("page", "1"))
            .andExpect(content().string(containsString("[1]")));
    }

    @Test
    void invalidCommentAndCsrfAndAnonymousRequestsAreRejected() throws Exception {
        String url = "/board/" + first.getId() + "/comments";
        for (String content : List.of("", "   ", "가".repeat(501))) {
            mvc.perform(withCsrf(post(url)).header("Authorization", basic("paging-writer")).param("content", content))
                .andExpect(redirectedUrl("/board/" + first.getId() + "?commentError#comments"));
        }
        mvc.perform(withCsrf(post(url)).param("content", "비로그인"))
            .andExpect(redirectedUrl("/login"));
        mvc.perform(post(url).header("Authorization", basic("paging-writer")).param("content", "토큰 없음"))
            .andExpect(status().isForbidden());
        mvc.perform(withCsrf(post("/board/999999/comments")).header("Authorization", basic("paging-writer")).param("content", "내용"))
            .andExpect(status().isNotFound());
        assertThat(comments.count()).isZero();
    }

    @Test
    void commentDeleteChecksParentAndOwnerNotPostWriter() throws Exception {
        Comment comment = seedComment(first, other, "다른 회원 댓글");
        String url = "/board/" + first.getId() + "/comments/" + comment.getId() + "/delete";
        mvc.perform(get("/board/" + first.getId()).header("Authorization", basic("paging-writer")))
            .andExpect(content().string(not(containsString("comments/" + comment.getId() + "/delete"))));
        mvc.perform(withCsrf(post(url)).header("Authorization", basic("paging-writer")))
            .andExpect(status().isForbidden());
        mvc.perform(withCsrf(post("/board/" + second.getId() + "/comments/" + comment.getId() + "/delete"))
                .header("Authorization", basic("paging-other")))
            .andExpect(status().isNotFound());
        assertThat(comments.existsById(comment.getId())).isTrue();
        mvc.perform(withCsrf(post(url)).header("Authorization", basic("paging-other")))
            .andExpect(redirectedUrl("/board/" + first.getId() + "?commentDeleted#comments"));
        assertThat(comments.existsById(comment.getId())).isFalse();
    }

    @Test
    void adminCanDeleteOthersCommentsAndUnknownCommentIs404() throws Exception {
        Comment comment = seedComment(first, other, "관리자 정리 대상");
        mvc.perform(withCsrf(post("/board/" + first.getId() + "/comments/" + comment.getId() + "/delete"))
                .header("Authorization", basic("paging-admin")))
            .andExpect(status().isFound());
        assertThat(comments.existsById(comment.getId())).isFalse();
        assertThat(posts.existsById(first.getId())).isTrue();
        mvc.perform(withCsrf(post("/board/" + first.getId() + "/comments/999999/delete"))
                .header("Authorization", basic("paging-admin")))
            .andExpect(status().isNotFound());
    }

    @Test
    void deletingPostCascadesOnlyItsOwnComments() throws Exception {
        Comment attached = seedComment(first, other, "함께 삭제");
        Comment unrelated = seedComment(second, other, "다른 글의 댓글");
        mvc.perform(withCsrf(post("/board/" + first.getId() + "/delete"))
                .header("Authorization", basic("paging-writer")))
            .andExpect(redirectedUrl("/board?deleted"));
        posts.flush();
        assertThat(posts.existsById(first.getId())).isFalse();
        assertThat(comments.existsById(attached.getId())).isFalse();
        assertThat(comments.existsById(unrelated.getId())).isTrue();
    }

    @Test
    void membersWithPostsOrCommentsCannotBeDeletedThroughApiOrHtml() throws Exception {
        Member commentOnly = member("comment-only", "댓글 작성 회원", "USER");
        seedComment(first, commentOnly, "남길 댓글");
        for (Member target : List.of(writer, commentOnly)) {
            mvc.perform(delete("/api/admin/members/" + target.getId()).header("Authorization", basic("paging-admin")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("작성한 글이나 댓글이 있는 회원은 삭제할 수 없습니다."));
            mvc.perform(withCsrf(post("/admin/members/" + target.getId() + "/delete"))
                    .header("Authorization", basic("paging-admin")))
                .andExpect(redirectedUrl("/admin/members"))
                .andExpect(flash().attribute("error", "작성한 글이나 댓글이 있는 회원은 삭제할 수 없습니다."));
            assertThat(members.existsById(target.getId())).isTrue();
        }
        Member empty = member("empty-member", "삭제 가능 회원", "USER");
        mvc.perform(delete("/api/admin/members/" + empty.getId()).header("Authorization", basic("paging-admin")))
            .andExpect(status().isNoContent());
        assertThat(members.existsById(empty.getId())).isFalse();
    }

    @SuppressWarnings("unchecked")
    private Page<Post> page(MvcResult result) {
        return (Page<Post>) result.getModelAndView().getModel().get("page");
    }

    private MockHttpServletRequestBuilder withCsrf(MockHttpServletRequestBuilder request) throws Exception {
        var result = mvc.perform(get("/login")).andReturn();
        var csrf = (CsrfToken) result.getRequest().getAttribute("_csrf");
        return request.session((MockHttpSession) result.getRequest().getSession())
            .param(csrf.getParameterName(), csrf.getToken());
    }

    private Member member(String username, String name, String role) {
        Member member = new Member();
        member.setUsername(username);
        member.setName(name);
        member.setRole(role);
        member.setPassword(encoder.encode("paging-test"));
        return members.save(member);
    }

    private Comment seedComment(Post post, Member author, String content) {
        Comment comment = new Comment();
        comment.setPost(post);
        comment.setAuthor(author);
        comment.setContent(content);
        comment.setCreatedAt(LocalDateTime.now());
        post.getComments().add(comment);
        return comments.saveAndFlush(comment);
    }

    private String basic(String username) {
        return "Basic " + Base64.getEncoder().encodeToString((username + ":paging-test").getBytes(StandardCharsets.UTF_8));
    }
}
