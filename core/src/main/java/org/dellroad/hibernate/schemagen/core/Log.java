
/*
 * Copyright (C) 2023 Archie L. Cobbs. All rights reserved.
 */

package org.dellroad.hibernate.schemagen.core;

public interface Log {
    boolean isDebugEnabled();
    void debug(String message);
    void info(String message);
}
