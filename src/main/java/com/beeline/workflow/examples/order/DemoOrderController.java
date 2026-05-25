package com.beeline.workflow.examples.order;

import com.beeline.workflow.core.config.WorkflowOptions;
import com.beeline.workflow.spring.api.WorkflowClient;
import com.beeline.workflow.spring.api.WorkflowHandle;
import org.springframework.web.bind.annotation.PathVariable;
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
        OrderWorkflow stub = client.newWorkflowStub(
                OrderWorkflow.class,
                WorkflowOptions.newBuilder().workflowId("order-" + request.getOrderId()).build());

        WorkflowHandle<String> handle = client.start(stub::process, request);

        return Map.of(
                "instanceId", handle.getInstanceId(),
                "workflowType", handle.getWorkflowType(),
                "state", stub.getState());
    }

    @PostMapping("/{id}/approve")
    public Map<String, Object> approve(@PathVariable("id") Long id, @RequestBody Map<String, String> body) {
        OrderWorkflow stub = client.newWorkflowStub(OrderWorkflow.class, id);
        stub.approve(body.getOrDefault("approver", "anonymous"));
        return Map.of("ok", true, "state", stub.getState());
    }

    @PostMapping("/{id}/state")
    public Map<String, Object> state(@PathVariable("id") Long id) {
        OrderWorkflow stub = client.newWorkflowStub(OrderWorkflow.class, id);
        return Map.of("state", stub.getState());
    }
}
