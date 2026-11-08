package com.hospio.housekeeping.repository;

import com.hospio.housekeeping.entity.CleaningTask;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CleaningTaskRepository extends JpaRepository<CleaningTask, String> {
    List<CleaningTask> findByHotelIdAndDueDateOrderByRoomNumber(String hotelId, java.time.LocalDate dueDate);

    List<CleaningTask> findByHotelIdAndAssignedToAndDueDateOrderByRoomNumber(String hotelId, String assignedTo, java.time.LocalDate dueDate);

    List<CleaningTask> findByHotelIdAndStatusOrderByDoneAt(String hotelId, CleaningTask.Status status);

    List<CleaningTask> findByRoomIdAndStatusIn(String roomId, java.util.Collection<CleaningTask.Status> statuses);
}
