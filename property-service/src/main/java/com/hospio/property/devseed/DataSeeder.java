package com.hospio.property.devseed;

import com.hospio.common.devseed.SeedFixtures;
import com.hospio.common.tenancy.Tenant;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.property.entity.Area;
import com.hospio.property.entity.Room;
import com.hospio.property.entity.RoomType;
import com.hospio.property.repository.AreaRepository;
import com.hospio.property.repository.RoomRepository;
import com.hospio.property.repository.RoomTypeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Simpas Ikeja's rooms (see {@link SeedFixtures}): four room types, four floors and 120 rooms,
 * created on start-up when {@code hospio.seed.enabled=true}. Only what is missing is created, so a
 * price or a room QA changed in Hotel setup stays as they left it.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "hospio.seed.enabled", havingValue = "true")
@RequiredArgsConstructor
public class DataSeeder implements ApplicationRunner {

    private final RoomTypeRepository roomTypes;
    private final AreaRepository areas;
    private final RoomRepository rooms;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        Tenant tenant = Tenant.systemFor(SeedFixtures.BUSINESS_ID, SeedFixtures.HOTEL_ID, "seed", "Seed");
        TenantContext.runWith(tenant, () -> {
            int types = 0;
            for (SeedFixtures.RoomTypeSeed t : SeedFixtures.ROOM_TYPES) {
                if (roomTypes.findById(t.id()).isEmpty()) {
                    RoomType rt = RoomType.builder().businessId(SeedFixtures.BUSINESS_ID).hotelId(SeedFixtures.HOTEL_ID)
                            .name(t.name()).description(t.description()).basePrice(t.basePrice())
                            .capacityAdults(t.adults()).capacityChildren(t.children()).facilities(t.facilities()).build();
                    rt.setId(t.id());
                    roomTypes.save(rt);
                    types++;
                }
            }
            int floors = 0;
            for (SeedFixtures.FloorSeed f : SeedFixtures.FLOORS) {
                if (areas.findById(f.id()).isEmpty()) {
                    Area a = Area.builder().businessId(SeedFixtures.BUSINESS_ID).hotelId(SeedFixtures.HOTEL_ID)
                            .name(f.name()).kind("FLOOR").sortOrder(f.sortOrder()).build();
                    a.setId(f.id());
                    areas.save(a);
                    floors++;
                }
            }
            int created = 0;
            for (SeedFixtures.RoomSeed r : SeedFixtures.rooms()) {
                if (rooms.findByHotelIdAndNumber(SeedFixtures.HOTEL_ID, r.number()).isEmpty()) {
                    rooms.save(Room.builder().businessId(SeedFixtures.BUSINESS_ID).hotelId(SeedFixtures.HOTEL_ID)
                            .roomTypeId(r.type().id()).areaId(r.floor().id()).number(r.number())
                            .floor(r.floor().block() + r.floor().floor()).build());
                    created++;
                }
            }
            if (types + floors + created > 0) {
                log.info("Seeded {} room types, {} floors and {} rooms for {}", types, floors, created, SeedFixtures.HOTEL_NAME);
            } else {
                log.info("Rooms for {} already present; nothing seeded", SeedFixtures.HOTEL_NAME);
            }
        });
    }
}
