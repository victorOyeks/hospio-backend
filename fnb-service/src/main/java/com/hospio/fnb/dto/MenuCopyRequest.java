package com.hospio.fnb.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.List;

/**
 * Copy another outlet's menu here. {@code categories}: only these (all when empty). {@code madeAtSource}: copied items stay made at
 * the outlet they came from (the patio sells the kitchen's starters, the kitchen still makes them). Items already here by name are
 * left alone.
 */
public record MenuCopyRequest(@NotBlank String fromOutletId, List<String> categories, Boolean madeAtSource) {
}
