package com.example.demo.model.repository;

import java.util.List;
import java.util.Optional;
import com.example.demo.model.domain.*;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EnrollmentRepository extends JpaRepository<Enrollment, Long> {
    boolean existsByMemberAndPost(Member member, Post post);
    boolean existsByMember(Member member);
    boolean existsByPost(Post post);

    @EntityGraph(attributePaths = "post")
    List<Enrollment> findByMember_UsernameOrderByIdDesc(String username);

    @Override
    @EntityGraph(attributePaths = {"member", "post"})
    Optional<Enrollment> findById(Long id);
}
