package com.beeline.workflow.persistence.repository;

import com.beeline.workflow.core.model.ActivityOptionOverride;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ActivityOptionOverrideRepository extends JpaRepository<ActivityOptionOverride, String> {
}