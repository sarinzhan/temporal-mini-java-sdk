package com.beeline.temporalmini.ui;

import com.beeline.temporalmini.InstanceRegistryRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@RestController
@RequestMapping("/ui")
public class UiAggregatorController {

    public record AggregatedState(List<NodeStateController.NodeState> nodes) {}

    private static final int HEARTBEAT_THRESHOLD_SECONDS = 30;

    private final InstanceRegistryRepository instanceRegistryRepository;
    private final RestClient restClient;

    public UiAggregatorController(InstanceRegistryRepository instanceRegistryRepository,
                                   RestClient restClient) {
        this.instanceRegistryRepository = instanceRegistryRepository;
        this.restClient = restClient;
    }

    @GetMapping("/state")
    public AggregatedState state() {
        LocalDateTime threshold = LocalDateTime.now().minusSeconds(HEARTBEAT_THRESHOLD_SECONDS);
        List<com.beeline.temporalmini.InstanceRegistryEntity> liveInstances =
                instanceRegistryRepository.findByLastHeartbeatAfter(threshold);

        List<NodeStateController.NodeState> nodes = new ArrayList<>();
        for (var instance : liveInstances) {
            try {
                NodeStateController.NodeState nodeState = restClient.get()
                        .uri(instance.getUrl() + "/internal/state")
                        .retrieve()
                        .body(NodeStateController.NodeState.class);
                if (nodeState != null) {
                    nodes.add(nodeState);
                }
            } catch (Exception ex) {
                log.warn("Failed to fetch state from instance {}: {}", instance.getUrl(), ex.getMessage());
            }
        }
        return new AggregatedState(nodes);
    }
}
