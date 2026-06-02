package com.beeline.workflow.sam.storage.api;

import com.beeline.workflow.sam.storage.model.Schedule;

import java.util.List;

public interface ScheduleRepository {
    List<Schedule> findReady();
}
