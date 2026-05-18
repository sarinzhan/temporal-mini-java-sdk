package com.beeline.workflow.web.dto;

public record AdminActionResponse(boolean ok, String message) {
    public static AdminActionResponse success() { return new AdminActionResponse(true, null); }
    public static AdminActionResponse success(String message) { return new AdminActionResponse(true, message); }
}
