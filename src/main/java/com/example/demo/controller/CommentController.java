package com.example.demo.controller;

import java.security.Principal;

import com.example.demo.model.service.CommentService;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

@Controller
@RequestMapping("/board/{postId}/comments")
public class CommentController {
    private final CommentService commentService;

    public CommentController(CommentService commentService) {
        this.commentService = commentService;
    }

    @PostMapping
    public String write(@PathVariable Long postId, @RequestParam(defaultValue = "") String content, Principal principal) {
        try {
            commentService.write(postId, content, principal.getName());
            return "redirect:/board/" + postId + "#comments";
        } catch (IllegalArgumentException exception) {
            return "redirect:/board/" + postId + "?commentError#comments";
        }
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long postId, @PathVariable Long id) {
        commentService.delete(postId, id);
        return "redirect:/board/" + postId + "?commentDeleted#comments";
    }
}
