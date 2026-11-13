package com.example.demo.controller;

import java.security.Principal;

import com.example.demo.model.domain.Post;
import com.example.demo.model.dto.PostForm;
import com.example.demo.model.service.PostService;
import com.example.demo.model.service.CommentService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
@RequestMapping("/board")
public class BoardController {

    private final PostService postService;
    private final CommentService commentService;

    public BoardController(PostService postService, CommentService commentService) {
        this.postService = postService;
        this.commentService = commentService;
    }

    @GetMapping({"", "/"})
    public String list(@RequestParam(defaultValue = "0") int page,
                       @RequestParam(required = false) String keyword,
                       @RequestParam(defaultValue = "all") String type, Model model) {
        String search = keyword == null ? null : keyword.trim();
        String scope = "writer".equals(type) ? "writer" : "all";
        var posts = postService.findPage(search, scope, page);
        model.addAttribute("posts", posts.getContent());
        model.addAttribute("page", posts);
        model.addAttribute("keyword", search);
        model.addAttribute("type", scope);
        return "board/list";
    }

    @GetMapping("/my")
    public String myPosts(Principal principal, @RequestParam(defaultValue = "0") int page, Model model) {
        var posts = postService.findMyPage(principal.getName(), page);
        model.addAttribute("posts", posts.getContent());
        model.addAttribute("page", posts);
        model.addAttribute("pageUrl", "/board/my");
        model.addAttribute("listTitle", "내가 쓴 글");
        return "board/list";
    }

    @GetMapping("/write")
    public String writeForm(Model model) {
        model.addAttribute("postForm", new PostForm());
        return "board/form";
    }

    @PostMapping("/write")
    public String write(@ModelAttribute PostForm postForm, Principal principal, Model model) {
        try {
            Post post = postService.write(postForm, principal.getName());
            return "redirect:/board/" + post.getId();
        } catch (IllegalArgumentException exception) {
            model.addAttribute("error", exception.getMessage());
            return "board/form";
        }
    }

    @GetMapping("/{id:\\d+}")
    public String detail(@PathVariable Long id, Model model) {
        Post post = postService.read(id);
        model.addAttribute("post", post);
        model.addAttribute("comments", commentService.findByPost(post));
        return "board/detail";
    }

    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable Long id, Model model) {
        Post post = postService.findForEdit(id);
        PostForm form = new PostForm();
        form.setTitle(post.getTitle());
        form.setContent(post.getContent());
        model.addAttribute("postForm", form);
        model.addAttribute("postId", id);
        return "board/form";
    }

    @PostMapping("/{id}/edit")
    public String edit(@PathVariable Long id, @ModelAttribute PostForm postForm, Model model) {
        try {
            postService.edit(id, postForm);
            return "redirect:/board/" + id;
        } catch (IllegalArgumentException exception) {
            model.addAttribute("error", exception.getMessage());
            model.addAttribute("postId", id);
            return "board/form";
        }
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id) {
        postService.delete(id);
        return "redirect:/board?deleted";
    }
}
