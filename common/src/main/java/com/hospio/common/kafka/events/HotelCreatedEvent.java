package com.hospio.common.kafka.events;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class HotelCreatedEvent extends HospioEvent {
    private String name;
    private String city;
    /** com.hospio.common.venue.VenueType name; null (older events) reads as HOTEL. */
    private String venueType;

    @Override
    public String summary() {
        return "Hotel " + name + " added";
    }
}
