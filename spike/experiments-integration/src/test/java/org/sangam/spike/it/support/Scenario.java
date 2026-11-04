package org.sangam.spike.it.support;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Marks a test method as one experiment scenario; its outcome and measurements go to {@code results/<E>.json}. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Scenario {
    String id();

    String name();

    /** SANGAM.md test ids covered (T02, T36, ...). */
    String[] tests() default {};

    /** False for an informational probe that records a finding but is not part of the failure-test pass rate. */
    boolean counted() default true;
}
