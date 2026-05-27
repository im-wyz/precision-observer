package com.nnu.rasterapi.repository;

import com.nnu.rasterapi.entity.Task;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TaskRepository extends JpaRepository<Task, String> {
}
