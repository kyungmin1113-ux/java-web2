package com.example.demo;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;

import com.example.demo.model.domain.Member;
import com.example.demo.model.domain.Post;
import com.example.demo.model.dto.PostForm;
import com.example.demo.model.repository.MemberRepository;
import com.example.demo.model.repository.PostRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
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
class BoardTests {

    @Autowired MockMvc mvc;
    @Autowired MemberRepository members;
    @Autowired PostRepository posts;
    @Autowired PasswordEncoder encoder;

    private Member writer;
    private Member other;
    private Post first;
    private Post latest;

    @BeforeEach
    void setup() {
        writer = member("board-writer", "작성자", "USER");
        other = member("board-other", "다른 회원", "USER");
        member("board-admin", "관리자", "ADMIN");
        first = seedPost("첫 번째 글", writer);
        seedPost("다른 사람의 글", other);
        latest = seedPost("최신 글", writer);
        posts.flush();
    }

    @Test
    void publicReadAndProtectedRoutes() throws Exception {
        mvc.perform(get("/board")).andExpect(status().isOk()).andExpect(view().name("board/list"))
            .andExpect(content().string(not(containsString("href=\"/board/write\""))));
        mvc.perform(get("/board/" + first.getId())).andExpect(status().isOk())
            .andExpect(content().string(not(containsString("/" + first.getId() + "/edit"))));
        for (String url : List.of("/board/write", "/board/my", "/board/" + first.getId() + "/edit")) {
            mvc.perform(get(url)).andExpect(status().isFound()).andExpect(redirectedUrl("/login"));
        }
        mvc.perform(withCsrf(post("/board/write"))).andExpect(status().isFound())
            .andExpect(redirectedUrl("/login"));
    }

    @Test
    void writeFixesAuthorAndIgnoresForgedFields() throws Exception {
        var result = mvc.perform(withCsrf(post("/board/write")).header("Authorization", basic("board-writer"))
                .param("title", "  새 게시글  ").param("content", "첫 줄\n둘째 줄")
                .param("author", "board-other").param("member_id", other.getId().toString())
                .param("viewCount", "999"))
            .andExpect(status().isFound()).andReturn();
        String location = result.getResponse().getRedirectedUrl();
        Post saved = posts.findById(Long.parseLong(location.substring(location.lastIndexOf('/') + 1))).orElseThrow();
        assertThat(saved.getTitle()).isEqualTo("새 게시글");
        assertThat(saved.getAuthor().getUsername()).isEqualTo("board-writer");
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNull();
        assertThat(saved.getViewCount()).isZero();
        mvc.perform(post("/board/write").header("Authorization", basic("board-writer"))
                .param("title", "CSRF 없음").param("content", "내용"))
            .andExpect(status().isForbidden());
    }

    @Test
    void invalidWriteKeepsInputAndDoesNotSave() throws Exception {
        long count = posts.count();
        for (String title : List.of("   ", "가".repeat(101))) {
            var result = mvc.perform(withCsrf(post("/board/write")).header("Authorization", basic("board-writer"))
                    .param("title", title).param("content", "유지되어야 하는 내용"))
                .andExpect(status().isOk()).andExpect(view().name("board/form"))
                .andExpect(model().attributeExists("error", "postForm")).andReturn();
            PostForm form = (PostForm) result.getModelAndView().getModel().get("postForm");
            assertThat(form.getTitle()).isEqualTo(title);
            assertThat(form.getContent()).isEqualTo("유지되어야 하는 내용");
        }
        mvc.perform(withCsrf(post("/board/write")).header("Authorization", basic("board-writer"))
                .param("title", "제목만").param("content", " "))
            .andExpect(model().attribute("error", "제목과 내용을 모두 입력하세요."));
        assertThat(posts.count()).isEqualTo(count);
    }

    @Test
    void writerCanEditAndInvalidEditKeepsMode() throws Exception {
        mvc.perform(get("/board/" + first.getId() + "/edit").header("Authorization", basic("board-writer")))
            .andExpect(status().isOk()).andExpect(model().attribute("postId", first.getId()));
        mvc.perform(withCsrf(post("/board/" + first.getId() + "/edit"))
                .header("Authorization", basic("board-writer"))
                .param("title", "").param("content", "수정 입력 유지"))
            .andExpect(view().name("board/form")).andExpect(model().attribute("postId", first.getId()))
            .andExpect(model().attributeExists("error"));
        assertThat(posts.findById(first.getId()).orElseThrow().getTitle()).isEqualTo("첫 번째 글");
        mvc.perform(withCsrf(post("/board/" + first.getId() + "/edit"))
                .header("Authorization", basic("board-writer"))
                .param("title", "  수정된 글  ").param("content", "수정된 내용"))
            .andExpect(redirectedUrl("/board/" + first.getId()));
        Post changed = posts.findById(first.getId()).orElseThrow();
        assertThat(changed.getTitle()).isEqualTo("수정된 글");
        assertThat(changed.getUpdatedAt()).isNotNull();
        assertThat(changed.getAuthor().getUsername()).isEqualTo("board-writer");
        assertThat(changed.getViewCount()).isZero();
    }

    @Test
    void otherUserAndAdminCannotEditSomeoneElsesPost() throws Exception {
        for (String username : List.of("board-other", "board-admin")) {
            mvc.perform(get("/board/" + first.getId() + "/edit").header("Authorization", basic(username)))
                .andExpect(status().isForbidden());
            mvc.perform(withCsrf(post("/board/" + first.getId() + "/edit"))
                    .header("Authorization", basic(username)).param("title", "변조").param("content", "변조"))
                .andExpect(status().isForbidden());
        }
        assertThat(posts.findById(first.getId()).orElseThrow().getTitle()).isEqualTo("첫 번째 글");
    }

    @Test
    void onlyWriterOrAdminCanDelete() throws Exception {
        mvc.perform(withCsrf(post("/board/" + first.getId() + "/delete"))
                .header("Authorization", basic("board-other")))
            .andExpect(status().isForbidden());
        assertThat(posts.existsById(first.getId())).isTrue();
        mvc.perform(withCsrf(post("/board/" + first.getId() + "/delete"))
                .header("Authorization", basic("board-writer")))
            .andExpect(redirectedUrl("/board?deleted"));
        mvc.perform(withCsrf(post("/board/" + latest.getId() + "/delete"))
                .header("Authorization", basic("board-admin")))
            .andExpect(redirectedUrl("/board?deleted"));
        assertThat(posts.existsById(first.getId())).isFalse();
        assertThat(posts.existsById(latest.getId())).isFalse();
    }

    @Test
    void countIncreasesOnlyOnDetailAndUnknownPostIs404() throws Exception {
        mvc.perform(get("/board"));
        mvc.perform(get("/board/" + first.getId() + "/edit").header("Authorization", basic("board-writer")));
        assertThat(posts.findById(first.getId()).orElseThrow().getViewCount()).isZero();
        for (int count = 1; count <= 3; count++) {
            mvc.perform(get("/board/" + first.getId())).andExpect(status().isOk())
                .andExpect(view().name("board/detail"));
            assertThat(posts.findById(first.getId()).orElseThrow().getViewCount()).isEqualTo(count);
        }
        mvc.perform(get("/board/999999")).andExpect(status().isNotFound());
        mvc.perform(get("/board/999999/edit").header("Authorization", basic("board-writer")))
            .andExpect(status().isNotFound());
    }

    @Test
    @SuppressWarnings("unchecked")
    void myPostsOnlyIncludesCurrentWriterInNewestOrder() throws Exception {
        var result = mvc.perform(get("/board/my").header("Authorization", basic("board-writer")))
            .andExpect(status().isOk()).andExpect(view().name("board/list"))
            .andExpect(model().attribute("listTitle", "내가 쓴 글")).andReturn();
        var mine = (List<Post>) result.getModelAndView().getModel().get("posts");
        assertThat(mine).extracting(Post::getId).containsExactly(latest.getId(), first.getId());
        mvc.perform(get("/board/my").header("Authorization", basic("board-admin")))
            .andExpect(status().isOk()).andExpect(content().string(containsString("아직 글이 없습니다.")));
    }

    @Test
    void detailEscapesHtmlAndShowsActionsByOwnership() throws Exception {
        first.setTitle("<script>alert(1)</script>");
        first.setContent("<img src=x onerror=alert(1)>\n내용");
        posts.saveAndFlush(first);
        mvc.perform(get("/board/" + first.getId()).header("Authorization", basic("board-writer")))
            .andExpect(content().string(containsString("&lt;script&gt;")))
            .andExpect(content().string(containsString("&lt;img")))
            .andExpect(content().string(containsString("/" + first.getId() + "/edit")));
        mvc.perform(get("/board/" + first.getId()).header("Authorization", basic("board-other")))
            .andExpect(content().string(not(containsString("/" + first.getId() + "/edit"))))
            .andExpect(content().string(not(containsString("/" + first.getId() + "/delete"))));
        mvc.perform(get("/board/" + first.getId()).header("Authorization", basic("board-admin")))
            .andExpect(content().string(containsString("관리자 삭제")))
            .andExpect(content().string(not(containsString("/" + first.getId() + "/edit"))));
    }

    private MockHttpServletRequestBuilder withCsrf(MockHttpServletRequestBuilder request) throws Exception {
        var login = mvc.perform(get("/login")).andReturn();
        var csrf = (CsrfToken) login.getRequest().getAttribute("_csrf");
        return request.session((MockHttpSession) login.getRequest().getSession())
            .param(csrf.getParameterName(), csrf.getToken());
    }

    private Member member(String username, String name, String role) {
        Member member = new Member();
        member.setUsername(username);
        member.setName(name);
        member.setRole(role);
        member.setPassword(encoder.encode("board-test"));
        return members.save(member);
    }

    private Post seedPost(String title, Member author) {
        Post post = new Post();
        post.setTitle(title);
        post.setContent("테스트 내용");
        post.setAuthor(author);
        post.setCreatedAt(LocalDateTime.now());
        return posts.save(post);
    }

    private String basic(String username) {
        return "Basic " + Base64.getEncoder().encodeToString((username + ":board-test").getBytes(StandardCharsets.UTF_8));
    }
}
