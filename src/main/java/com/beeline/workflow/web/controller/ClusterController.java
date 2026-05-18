package com.beeline.workflow.web.controller;

import com.beeline.workflow.core.model.InstanceRegistryEntity;
import com.beeline.workflow.core.model.Task;
import com.beeline.workflow.engine.cluster.InstanceRegistryService;
import com.beeline.workflow.engine.worker.WorkerLoopImpl;
import com.beeline.workflow.persistence.repository.InstanceRegistryRepository;
import com.beeline.workflow.persistence.repository.TaskRepository;
import com.beeline.workflow.spring.autoconfigure.WorkflowProperties;
import com.beeline.workflow.web.dto.ClusterNodesResponse;
import com.beeline.workflow.web.dto.LocalStateResponse;
import com.beeline.workflow.web.dto.NodeInfo;
import com.beeline.workflow.web.dto.PoolStats;
import com.beeline.workflow.web.dto.RunningTaskDto;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Collections;
import java.util.List;

@RestController
@RequestMapping("/workflow/api/cluster")
@CrossOrigin(origins = "*")
public class ClusterController {

    private final WorkflowProperties properties;
    private final InstanceRegistryRepository instanceRegistryRepository;
    private final TaskRepository taskRepository;
    private final WorkerLoopImpl workerLoop;

    public ClusterController(WorkflowProperties properties,
                             InstanceRegistryRepository instanceRegistryRepository,
                             TaskRepository taskRepository,
                             WorkerLoopImpl workerLoop) {
        this.properties = properties;
        this.instanceRegistryRepository = instanceRegistryRepository;
        this.taskRepository = taskRepository;
        this.workerLoop = workerLoop;
    }

    @GetMapping("/nodes")
    public ClusterNodesResponse nodes() {
        var instance = properties.getInstance();
        String selfId = instance.getId();

        List<NodeInfo> nodes;
        if (!instance.isMultiInstance()) {
            nodes = Collections.emptyList();
        } else {
            Instant cutoff = Instant.now().minus(InstanceRegistryService.STALE_THRESHOLD);
            List<InstanceRegistryEntity> live = instanceRegistryRepository.findLive(cutoff);
            nodes = live.stream()
                    .map(e -> new NodeInfo(
                            e.getId(),
                            e.getInternalUrl(),
                            e.getExternalUrl(),
                            e.getLastHeartbeat(),
                            e.getId().equals(selfId)))
                    .toList();
        }
        return new ClusterNodesResponse(selfId, nodes);
    }

    @GetMapping("/local")
    public LocalStateResponse local() {
        String selfId = properties.getInstance().getId();

        WorkerLoopImpl.PoolSnapshot snap = workerLoop.getPoolStats();
        PoolStats pool = new PoolStats(snap.active(), snap.queue(), snap.max());

        List<Task> runningTasks = taskRepository.findRunningByNode(selfId);
        List<RunningTaskDto> running = runningTasks.stream()
                .map(t -> new RunningTaskDto(
                        t.getId(),
                        t.getWorkflowId(),
                        t.getLockedAt(),
                        t.getLockedUntil()))
                .toList();

        return new LocalStateResponse(selfId, pool, running);
    }
}
