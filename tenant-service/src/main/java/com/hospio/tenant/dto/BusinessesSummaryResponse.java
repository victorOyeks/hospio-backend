package com.hospio.tenant.dto;

import com.hospio.tenant.entity.Business;
import java.util.Map;

/** The platform dashboard's counts: businesses in total and by stage, hotels and rooms across all of them. */
public record BusinessesSummaryResponse(long total, Map<Business.Stage, Long> byStage, long hotels, long rooms) {
}
