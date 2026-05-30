package com.beeline.workflow.core.api;

import com.beeline.workflow.engine.context.ActivityExecutionContext;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ActivityExecutionTest {

    @Test
    void keyFormatIsWfWorkflowSeq() {
        assertEquals("wf:42:1", new ActivityExecution(42L, 1, 1).idempotencyKey());
    }

    @Test
    void keyIsIndependentOfAttemptButDependsOnSeq() {
        // Same activity, different attempt → SAME key (so downstream dedups a retry).
        assertEquals(new ActivityExecution(42L, 1, 1).idempotencyKey(),
                new ActivityExecution(42L, 1, 5).idempotencyKey());
        // Different command position → different key.
        assertNotEquals(new ActivityExecution(42L, 1, 1).idempotencyKey(),
                new ActivityExecution(42L, 2, 1).idempotencyKey());
    }

    @Test
    void requireThrowsOutsideAnActivityBody() {
        ActivityExecutionContext.clear();
        assertThrows(IllegalStateException.class, ActivityExecutionContext::require);
    }

    @Test
    void contextIsReadableWhileBoundAndGoneAfterClear() {
        ActivityExecution exec = new ActivityExecution(7L, 3, 2);
        ActivityExecutionContext.set(exec);
        try {
            assertSame(exec, ActivityExecutionContext.require());
            assertEquals("wf:7:3", ActivityExecutionContext.require().idempotencyKey());
        } finally {
            ActivityExecutionContext.clear();
        }
        assertNull(ActivityExecutionContext.current());
    }
}
