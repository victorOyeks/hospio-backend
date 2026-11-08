package com.hospio.identity.service;

import com.hospio.common.exception.ForbiddenException;
import com.hospio.common.security.CurrentUser;
import com.hospio.common.security.HospioUserDetails;
import com.hospio.identity.dto.SupportAccessRequest;
import com.hospio.identity.entity.SupportAccessGrant;
import com.hospio.identity.repository.SupportAccessGrantRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Platform admins may look into a business as its owner, with a logged reason, for a short while. */
@Service
@RequiredArgsConstructor
public class SupportAccessService {

    private final SupportAccessGrantRepository repository;

    @Transactional
    public SupportAccessGrant open(SupportAccessRequest request) {
        HospioUserDetails caller = CurrentUser.require();
        if (!caller.isPlatform()) {
            throw new ForbiddenException("Only H.M.S staff can open support access");
        }
        int minutes = request.minutes() == null ? 60 : Math.min(request.minutes(), 240);
        return repository.save(SupportAccessGrant.builder()
                .adminId(caller.getUserId())
                .businessId(request.businessId())
                .reason(request.reason())
                .expiresAt(Instant.now().plus(minutes, ChronoUnit.MINUTES))
                .build());
    }

    @Transactional(readOnly = true)
    public List<SupportAccessGrant> mine() {
        return repository.findByAdminIdOrderByCreatedAtDesc(CurrentUser.require().getUserId());
    }
}
