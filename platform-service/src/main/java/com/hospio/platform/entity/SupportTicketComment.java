package com.hospio.platform.entity;

import com.hospio.common.entity.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

/** A note on a ticket. Internal notes stay inside H.M.S; the others are sent to the person who opened it. */
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "support_ticket_comments")
public class SupportTicketComment extends TenantEntity {

    @Column(name = "ticket_id", nullable = false, length = 36)
    private String ticketId;

    @Column(name = "author_id", length = 36)
    private String authorId;

    @Column(name = "author_name", length = 150)
    private String authorName;

    @Column(nullable = false, length = 4000)
    private String body;

    @Column(nullable = false)
    @lombok.Builder.Default
    private boolean internal = false;
}
