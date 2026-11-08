package com.hospio.maintenance.repository;

import com.hospio.maintenance.entity.ScheduledJobRun;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ScheduledJobRunRepository extends JpaRepository<ScheduledJobRun, String> {
    List<ScheduledJobRun> findByJobIdOrderByDoneOnDesc(String jobId);
}
