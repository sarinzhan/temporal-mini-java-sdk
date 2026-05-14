package com.beeline.temporalmini.ui;

import com.beeline.temporalmini.InstanceRegistryService;
import com.beeline.temporalmini.WorkflowRuntimeRegistry;
import com.beeline.temporalmini.autoconfigure.WorkflowCoreAutoConfiguration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/internal")
public class NodeStateController {

    public record RunningTaskDto(Long workflowId, long startedAtEpochMs) {}

    public record NodeState(
            String nodeId,
            String nodeUrl,
            int queueSize,
            int activeCount,
            List<RunningTaskDto> runningTasks
    ) {}

    private final InstanceRegistryService instanceRegistryService;
    private final WorkflowRuntimeRegistry runtimeRegistry;
    private final ThreadPoolTaskExecutor executor;

    public NodeStateController(InstanceRegistryService instanceRegistryService,
                               WorkflowRuntimeRegistry runtimeRegistry,
                               @Qualifier(WorkflowCoreAutoConfiguration.EXECUTOR_BEAN)
                               ThreadPoolTaskExecutor executor) {
        this.instanceRegistryService = instanceRegistryService;
        this.runtimeRegistry = runtimeRegistry;
        this.executor = executor;
    }

    @GetMapping("/state")
    public NodeState state() {
        List<RunningTaskDto> tasks = runtimeRegistry.snapshot().entrySet().stream()
                .map(e -> new RunningTaskDto(e.getKey(), e.getValue()))
                .toList();
        return new NodeState(
                instanceRegistryService.getInstanceId(),
                instanceRegistryService.getInstanceUrl(),
                executor.getThreadPoolExecutor().getQueue().size(),
                executor.getActiveCount(),
                tasks
        );
    }
}
