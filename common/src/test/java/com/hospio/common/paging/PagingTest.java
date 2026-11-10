package com.hospio.common.paging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hospio.common.exception.BadRequestException;
import com.hospio.common.response.PagedResponse;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;

class PagingTest {

    @Test
    void sizeDefaultsTo25AndIsOneTo100() {
        assertThat(Paging.size(null)).isEqualTo(25);
        assertThat(Paging.size(1)).isEqualTo(1);
        assertThat(Paging.size(100)).isEqualTo(100);
        assertThatThrownBy(() -> Paging.size(101)).isInstanceOf(BadRequestException.class).hasMessage("size is 1 to 100");
        assertThatThrownBy(() -> Paging.size(0)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> Paging.page(-1)).isInstanceOf(BadRequestException.class);
        assertThat(Paging.requested(null, null)).isFalse();
        assertThat(Paging.requested(0, null)).isTrue();
        assertThat(Paging.requested(null, 10)).isTrue();
    }

    @Test
    void sortTakesAllowedFieldsOnlyAndKeepsTheFallbackAsTieBreaker() {
        Sort fallback = Sort.by(Sort.Direction.DESC, "createdAt");
        assertThat(Paging.sort(null, Map.of("amount", "amount"), fallback)).isEqualTo(fallback);
        Sort s = Paging.sort("amount,asc", Map.of("amount", "amount"), fallback);
        assertThat(s.getOrderFor("amount").getDirection()).isEqualTo(Sort.Direction.ASC);
        assertThat(s.getOrderFor("createdAt")).isNotNull();
        assertThat(Paging.sort("amount", Map.of("amount", "amount"), fallback).getOrderFor("amount").isDescending()).isTrue();
        assertThatThrownBy(() -> Paging.sort("password", Map.of("amount", "amount"), fallback)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> Paging.sort("amount,up", Map.of("amount", "amount"), fallback)).isInstanceOf(BadRequestException.class);
    }

    @Test
    void sliceCutsPagesWithoutGapsOrOverlap() {
        List<Integer> all = IntStream.range(0, 48).boxed().toList();
        PagedResponse<Integer> p0 = Paging.slice(all, 0, 25, i -> i);
        PagedResponse<Integer> p1 = Paging.slice(all, 1, 25, i -> i);
        PagedResponse<Integer> p2 = Paging.slice(all, 2, 25, i -> i);
        assertThat(p0.content()).hasSize(25).startsWith(0);
        assertThat(p1.content()).hasSize(23).startsWith(25).endsWith(47);
        assertThat(p2.content()).isEmpty();
        assertThat(p1.totalElements()).isEqualTo(48);
        assertThat(p1.totalPages()).isEqualTo(2);
        assertThat(p0.first()).isTrue();
        assertThat(p1.last()).isTrue();
    }

    @Test
    void likeEscapesWildcards() {
        assertThat(Paging.like(" A%b_ ")).isEqualTo("%a\\%b\\_%");
    }
}
