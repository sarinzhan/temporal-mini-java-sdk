package com.beeline.workflow.examples.order;

public class OrderRequest {
    private String orderId;
    private int amount;

    public OrderRequest() {}
    public OrderRequest(String orderId, int amount) {
        this.orderId = orderId;
        this.amount = amount;
    }

    public String getOrderId() { return orderId; }
    public void setOrderId(String orderId) { this.orderId = orderId; }
    public int getAmount() { return amount; }
    public void setAmount(int amount) { this.amount = amount; }
}
