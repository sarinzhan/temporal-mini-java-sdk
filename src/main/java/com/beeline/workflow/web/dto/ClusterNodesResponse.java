package com.beeline.workflow.web.dto;

import java.util.List;

public record ClusterNodesResponse(
        String self,
        List<NodeInfo> nodes
) {}
