package com.hospio.platform.repository;

import com.hospio.platform.entity.HotelApplicationNote;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HotelApplicationNoteRepository extends JpaRepository<HotelApplicationNote, String> {

    List<HotelApplicationNote> findByApplicationIdOrderByCreatedAtAscIdAsc(String applicationId);
}
