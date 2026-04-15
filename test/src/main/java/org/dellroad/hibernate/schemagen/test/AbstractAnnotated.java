
/*
 * Copyright (C) 2023 Archie L. Cobbs. All rights reserved.
 */

package org.dellroad.hibernate.schemagen.test;

import java.util.HashMap;
import java.util.Map;

/**
 * Support superclass for annotated entity types.
 */
public abstract class AbstractAnnotated extends AbstractPersistent {

    private Map<String, String> annotations = new HashMap<>();

    protected AbstractAnnotated() {
    }

    /**
     * Get annotation map for this instance.
     */
    // NOTE: subclass must override and provide JPA annotations
    public Map<String, String> getAnnotations() {
        return this.annotations;
    }
    public void setAnnotations(Map<String, String> annotations) {
        this.annotations = annotations;
    }
}
