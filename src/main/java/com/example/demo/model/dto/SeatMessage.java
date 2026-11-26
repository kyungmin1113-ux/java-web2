package com.example.demo.model.dto;

import com.example.demo.model.domain.Post;

// 변경 당시 값을 복사하여 커밋 이후에 전달한다.
public record SeatMessage(Long postId, int appliedCount, int capacity, int remaining) {
    public static SeatMessage from(Post post) {
        return new SeatMessage(post.getId(), post.getAppliedCount(), post.getCapacity(), post.getRemaining());
    }
}
