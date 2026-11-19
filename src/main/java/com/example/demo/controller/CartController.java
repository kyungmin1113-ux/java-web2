package com.example.demo.controller;

import java.security.Principal;
import java.util.LinkedHashSet;
import java.util.Set;
import jakarta.servlet.http.HttpSession;
import com.example.demo.model.service.PostService;
import com.example.demo.model.service.EnrollmentService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/cart")
public class CartController {
    private final PostService postService;
    private final EnrollmentService enrollmentService;

    public CartController(PostService postService, EnrollmentService enrollmentService) {
        this.postService = postService;
        this.enrollmentService = enrollmentService;
    }

    @GetMapping
    public String cart(HttpSession session, Principal principal, Model model) {
        var ids = getCart(session);
        var posts = postService.findAllById(ids);
        var missing = new LinkedHashSet<>(ids);
        posts.forEach(post -> missing.remove(post.getId()));
        model.addAttribute("cartPosts", posts);
        model.addAttribute("missingPostIds", missing);
        model.addAttribute("enrollments", enrollmentService.findMine(principal.getName()));
        return "cart";
    }

    @PostMapping("/{postId:\\d+}")
    public String add(@PathVariable Long postId, HttpSession session) {
        var post = postService.findById(postId);
        if (!post.isRecruit()) return "redirect:/board/" + postId;
        getCart(session).add(postId);
        return "redirect:/cart";
    }

    @PostMapping("/{postId}/remove")
    public String remove(@PathVariable Long postId, HttpSession session) {
        getCart(session).remove(postId);
        return "redirect:/cart";
    }

    @PostMapping("/confirm")
    public String confirm(HttpSession session, Principal principal, RedirectAttributes redirect) {
        Set<Long> cart = getCart(session);
        try {
            int count = enrollmentService.confirm(principal.getName(), cart);
            cart.clear();
            redirect.addFlashAttribute("message", count + "개 모집글 신청이 확정되었습니다.");
        } catch (IllegalStateException exception) {
            redirect.addFlashAttribute("error", exception.getMessage());
        }
        return "redirect:/cart";
    }

    @PostMapping("/enrollments/{id}/cancel")
    public String cancel(@PathVariable Long id, RedirectAttributes redirect) {
        try {
            enrollmentService.cancel(id);
            redirect.addFlashAttribute("message", "신청이 취소되었습니다.");
        } catch (IllegalStateException exception) {
            redirect.addFlashAttribute("error", exception.getMessage());
        }
        return "redirect:/cart";
    }

    @SuppressWarnings("unchecked")
    private Set<Long> getCart(HttpSession session) {
        var cart = (Set<Long>) session.getAttribute("cart");
        if (cart == null) {
            cart = new LinkedHashSet<>();
            session.setAttribute("cart", cart);
        }
        return cart;
    }
}
