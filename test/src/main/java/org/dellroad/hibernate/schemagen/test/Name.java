
/*
 * Copyright (C) 2023 Archie L. Cobbs. All rights reserved.
 */

package org.dellroad.hibernate.schemagen.test;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.validation.constraints.Size;

import java.util.Objects;

/**
 * Contains a person's name.
 */
@Embeddable
public class Name {

    private String lastName;
    private String firstName;
    private String middleName;

    /**
     * Get the person's last name.
     */
    @Column(length = 255)
    @Size(max = 255)
    public String getLastName() {
        return this.lastName;
    }
    public void setLastName(String lastName) {
        this.lastName = lastName;
    }

    /**
     * Get the person's first name.
     */
    @Column(length = 255)
    @Size(max = 255)
    public String getFirstName() {
        return this.firstName;
    }
    public void setFirstName(String firstName) {
        this.firstName = firstName;
    }

    /**
     * Get the person's middle name.
     */
    @Column(length = 255)
    @Size(max = 255)
    public String getMiddleName() {
        return this.middleName;
    }
    public void setMiddleName(String middleName) {
        this.middleName = middleName;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == null || !(obj instanceof Name))
            return false;
        final Name that = (Name)obj;
        return Objects.equals(this.lastName, that.lastName)
          && Objects.equals(this.firstName, that.firstName)
          && Objects.equals(this.middleName, that.middleName);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(this.lastName)
          ^ Objects.hashCode(this.firstName)
          ^ Objects.hashCode(this.middleName);
    }
}
