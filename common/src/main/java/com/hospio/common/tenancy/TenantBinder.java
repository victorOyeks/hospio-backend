package com.hospio.common.tenancy;

import com.hospio.common.kafka.events.HospioEvent;
import java.sql.PreparedStatement;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * For code that runs inside a transaction opened before the tenant was known: sets the thread tenant
 * and the transaction-local Postgres settings. Kafka listeners call {@link #bind(HospioEvent)} first.
 */
@Component
@RequiredArgsConstructor
public class TenantBinder {

    private static final String BIND_LOCAL = "SELECT set_config('app.business_id', ?, true), set_config('app.hotel_id', ?, true), set_config('app.scope', ?, true)";

    private final JdbcTemplate jdbc;

    public Tenant bind(HospioEvent event) {
        return bind(Tenant.systemFor(event.getBusinessId(), event.getHotelId(), event.getActorId(), event.getActorName()));
    }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public Tenant bind(Tenant tenant) {
        TenantContext.set(tenant);
        jdbc.execute(BIND_LOCAL, (PreparedStatement ps) -> {
            ps.setString(1, tenant.businessId() == null ? "" : tenant.businessId());
            ps.setString(2, tenant.hotelId() == null ? "" : tenant.hotelId());
            ps.setString(3, tenant.scope());
            ps.execute();
            return null;
        });
        return tenant;
    }
}
