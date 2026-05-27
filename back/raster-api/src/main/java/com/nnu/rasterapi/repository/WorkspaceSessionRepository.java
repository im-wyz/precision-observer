package com.nnu.rasterapi.repository;

import com.nnu.rasterapi.entity.WorkspaceSession;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface WorkspaceSessionRepository extends JpaRepository<WorkspaceSession, String> {

    @Query("SELECT w FROM WorkspaceSession w ORDER BY w.updatedAt DESC")
    List<WorkspaceSession> findRecent(Pageable pageable);
}
