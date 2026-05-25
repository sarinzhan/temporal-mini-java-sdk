package com.beeline.workflow.core.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a signal handler on a workflow class. A signal handler mutates workflow
 * fields in response to an external {@code SignalBus.send(...)}; the {@code await(condition)}
 * in the entry method then observes the mutated state and unblocks.
 *
 * <p>Signal handlers are <b>pure field mutators</b>: they must not issue workflow commands
 * ({@code Workflow.activity/sleep/await/sideEffect}). The engine re-delivers every recorded
 * {@code SIGNAL_RECEIVED} event at the start of each turn to reconstruct signal-driven state
 * deterministically, so a handler that issued commands would corrupt replay.
 *
 * <p>A handler may take zero parameters (presence-only signal) or exactly one parameter
 * (the deserialized signal payload).
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface SignalMethod {
    String name() default "";
}
