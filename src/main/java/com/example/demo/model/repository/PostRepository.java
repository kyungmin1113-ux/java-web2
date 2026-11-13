package com.example.demo.model.repository;

import java.util.List;
import java.util.Optional;

import com.example.demo.model.domain.Post;
import com.example.demo.model.domain.Member;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PostRepository extends JpaRepository<Post, Long> {

    @Override
    @EntityGraph(attributePaths = "author")
    Page<Post> findAll(Pageable pageable);

    @EntityGraph(attributePaths = "author")
    @Query("select p from Post p where p.title like %:keyword% or p.content like %:keyword%")
    Page<Post> search(@Param("keyword") String keyword, Pageable pageable);

    @EntityGraph(attributePaths = "author")
    @Query("select p from Post p where p.author.name like %:name%")
    Page<Post> searchByWriter(@Param("name") String name, Pageable pageable);

    @EntityGraph(attributePaths = "author")
    Page<Post> findByAuthor_Username(String username, Pageable pageable);

    boolean existsByAuthor(Member author);

    @Override
    @EntityGraph(attributePaths = "author")
    List<Post> findAll(Sort sort);

    @Override
    @EntityGraph(attributePaths = "author")
    Optional<Post> findById(Long id);

    @EntityGraph(attributePaths = "author")
    List<Post> findByAuthor_UsernameOrderByIdDesc(String username);

    // DB에서 더하여 동시에 상세 화면을 열어도 증가분이 유실되지 않게 한다.
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Post p set p.viewCount = p.viewCount + 1 where p.id = :id")
    int incrementViewCount(@Param("id") Long id);
}
