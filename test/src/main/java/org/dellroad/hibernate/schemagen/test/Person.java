
/*
 * Copyright (C) 2023 Archie L. Cobbs. All rights reserved.
 */

package org.dellroad.hibernate.schemagen.test;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapKeyColumn;
import jakarta.persistence.Table;

import java.util.Map;

/**
 * Information about a person.
 */
@Entity
@Table(indexes = {
  @Index(name = "idx_Person_lastName", columnList = "lastName"),
})
public class Person extends AbstractAnnotated {

    private Name name = new Name();

    /**
     * Get the name of this person.
     */
    @Embedded
    public Name getName() {
        return this.name;
    }
    public void setName(Name name) {
        this.name = name != null ? name : new Name();
    }

    @ElementCollection
    @MapKeyColumn(name = "name", length = 180)
    @Column(name = "value", columnDefinition = "mediumtext", nullable = false)
    @CollectionTable(name = "PersonAnnotation",
      joinColumns = @JoinColumn(name = "person"),
      indexes = @Index(name = "idx_PersonAnnotation_name", columnList = "name"),
      foreignKey = @ForeignKey(name = "FK287AFF4A0F6ED11"))
    @Override
    public Map<String, String> getAnnotations() {
        return super.getAnnotations();
    }
}
