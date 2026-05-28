package com.beeline.workflow.examples.order;

import com.beeline.workflow.spring.api.WorkflowClient;
import com.beeline.workflow.spring.api.WorkflowHandle;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/demo/order")
public class DemoOrderController {

    private final WorkflowClient client;

    public DemoOrderController(WorkflowClient client) {
        this.client = client;
    }

    @PostMapping("/start")
    public Map<String, Object> start(@RequestBody OrderRequest request) {
        WorkflowHandle<String> handle = client.start(OrderWorkflow.class, request);
        return Map.of(
                "workflowId", handle.getInstanceId(),
                "workflowType", handle.getWorkflowType());
    }
}
