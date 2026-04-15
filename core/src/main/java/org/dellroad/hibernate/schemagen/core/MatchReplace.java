
/*
 * Copyright (C) 2023 Archie L. Cobbs. All rights reserved.
 */

package org.dellroad.hibernate.schemagen.core;

import java.util.regex.Pattern;

public class MatchReplace {

    private final Pattern pattern;
    private final String replacement;
    private final boolean modificationRequired;

    public MatchReplace(Pattern pattern, String replacement, boolean modificationRequired) {
        if (pattern == null)
            throw new IllegalArgumentException("null pattern");
        if (pattern == null)
            throw new IllegalArgumentException("null replacement");
        this.pattern = pattern;
        this.replacement = replacement;
        this.modificationRequired = modificationRequired;
    }

    public Pattern getPattern() {
        return this.pattern;
    }

    public String getReplacement() {
        return this.replacement;
    }

    public boolean isModificationRequired() {
        return this.modificationRequired;
    }

    public String applyTo(String input) {
        if (input == null)
            throw new IllegalArgumentException("null input");
        return this.pattern.matcher(input).replaceAll(this.replacement);
    }
}
