package com.beeline.workflow.core.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

@Getter
@Setter
@Entity
@Table(name = "activity_results", schema = "wflow",
        uniqueConstraints = @UniqueConstraint(columnNames = {"workflow_id", "activity_name"}))
public class ActivityResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "workflow_id", nullable = false)
    private Long workflowId;

    @Column(name = "activity_name", nullable = false)
    private String activityName;

    @Column(nullable = false)
    private String status;

    @Column(name = "result", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String result;

    @Column(columnDefinition = "text")
    private String error;

    @Column(name = "result_type")
    private String resultType;

    @Column(nullable = false)
    private int attempt = 0;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
