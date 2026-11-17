package com.hospio.payment.pos;

import com.hospio.common.entity.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

/** One provider's keys for one hotel (encrypted; {@code masked} is what people see). A hotel can hold several providers. */
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "hotel_pos_keys")
public class HotelPosKey extends TenantEntity {

    @Column(nullable = false, length = 20)
    private String provider;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String secrets;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String masked;

    @Column(name = "updated_by_name", length = 150)
    private String updatedByName;
}
