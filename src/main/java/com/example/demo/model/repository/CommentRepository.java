package com.example.demo.model.repository;

import java.util.List;
import java.util.Optional;

import com.example.demo.model.domain.Comment;
import com.example.demo.model.domain.Member;
import com.example.demo.model.domain.Post;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CommentRepository extends JpaRepository<Comment, Long> {
    @EntityGraph(attributePaths = "author")
    List<Comment> findByPostOrderByIdAsc(Post post);

    @Override
    @EntityGraph(attributePaths = {"author", "post"})
    Optional<Comment> findById(Long id);

    boolean existsByAuthor(Member author);
}
