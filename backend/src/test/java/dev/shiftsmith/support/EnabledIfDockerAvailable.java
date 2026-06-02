package dev.shiftsmith.support;

import org.junit.jupiter.api.extension.ExtendWith;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Runs the annotated test (class or method) only when Docker is reachable. */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@ExtendWith(DockerAvailableCondition.class)
public @interface EnabledIfDockerAvailable {
}
