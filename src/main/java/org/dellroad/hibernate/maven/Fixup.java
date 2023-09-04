
/*
 * Copyright (C) 2023 Archie L. Cobbs. All rights reserved.
 */

package org.dellroad.hibernate.maven;

import java.util.Optional;
import java.util.regex.Pattern;

import org.apache.maven.plugins.annotations.Parameter;

public class Fixup {

    /**
     * The regular expression to match against the generated schema DDL.
     *
     * <p>
     * This must be a valid regular expression suitable as a {@link Pattern}.
     */
    @Parameter(required = true)
    private String pattern;

    /**
     * The replacement expression to apply to all matches found.
     *
     * <p>
     * This must be a valid regular expression replacement string suitable for {@code Matcher.replaceAll()}.
     */
    @Parameter(required = true)
    private String replacement;

    public String applyTo(String string) {
        if (this.pattern == null)
            throw new IllegalArgumentException("no pattern specified");
        // Note: Maven passes empty strings as nulls, so map them here
        final String replace = Optional.ofNullable(this.replacement).orElse("");
        final Pattern regex;
        try {
            regex = Pattern.compile(this.pattern);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("invalid regular expression: " + e.getMessage(), e);
        }
        return regex.matcher(string).replaceAll(replace);
    }
}
