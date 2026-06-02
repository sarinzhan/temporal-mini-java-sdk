package com.beeline.workflow.sam.core;

import com.beeline.workflow.sam.core.engine.WorkflowEngine;
import com.beeline.workflow.sam.storage.api.ScheduleRepository;
import com.beeline.workflow.sam.storage.api.TaskRepository;
import com.beeline.workflow.sam.storage.api.WorkflowIntanceRepository;
import com.beeline.workflow.sam.storage.model.Schedule;
import com.beeline.workflow.sam.storage.model.Task;
import com.beeline.workflow.sam.storage.model.TaskStatus;
import com.beeline.workflow.sam.storage.model.WorkflowInstance;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public class WakeupScheduledWorfklows {

    private final ScheduleRepository scheduleRepository;
    private final WorkflowIntanceRepository workflowIntanceRepository;
    private final TaskRepository taskRepository;

    private final TransactionTemplate transactionTemplate;

    public WakeupScheduledWorfklows(
            ScheduleRepository scheduleRepository,
            WorkflowIntanceRepository workflowIntanceRepository,
            TaskRepository taskRepository,
            TransactionTemplate transactionTemplate
    ) {
        this.scheduleRepository = scheduleRepository;
        this.workflowIntanceRepository = workflowIntanceRepository;
        this.taskRepository = taskRepository;
        this.transactionTemplate = transactionTemplate;
    }


    public void wakeup(){
        List<Schedule> ready = scheduleRepository.findReady();
        for(Schedule schedule : ready){
            Optional<WorkflowInstance> byId = workflowIntanceRepository.findById(
                    schedule.getWorkflowId()
            );

            if(byId.isEmpty()){
                return;
            }

            WorkflowInstance workflowInstance = byId.get();

            Task task = new Task();
            task.setTaskStatus(TaskStatus.WAITING);
            task.setCreatedAt(OffsetDateTime.now());
            task.setWorkflowInstanceId(workflowInstance.getId());
        }
    }
}
