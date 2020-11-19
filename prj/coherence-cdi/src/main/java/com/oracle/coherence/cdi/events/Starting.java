/*
 * Copyright (c) 2020 Oracle and/or its affiliates.
 *
 * Licensed under the Universal Permissive License v 1.0 as shown at
 * http://oss.oracle.com/licenses/upl.
 */
package com.oracle.coherence.cdi.events;

import javax.enterprise.util.AnnotationLiteral;
import javax.inject.Qualifier;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * A qualifier annotation used for any STARTING event.
 *
 * @author Jonathan Knight  2020.11.10
 * @since 20.12
 */
@Qualifier
@Documented
@Retention(RetentionPolicy.RUNTIME)
public @interface Starting
    {
    /**
     * An annotation literal for the {@link Starting}
     * annotation.
     */
    class Literal
            extends AnnotationLiteral<Starting>
            implements Starting
        {
        public static final Literal INSTANCE = new Literal();
        }
    }
