
/*
 * Copyright (C) 2023 Archie L. Cobbs. All rights reserved.
 */

package org.dellroad.hibernate.schemagen.test;

import jakarta.persistence.Column;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreRemove;
import jakarta.persistence.PreUpdate;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;

/**
 * Support super-class for persistent model classes.
 */
@MappedSuperclass
public abstract class AbstractPersistent {

    private long id;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    protected AbstractPersistent() {
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(nullable = false)
    public long getId() {
        return this.id;
    }
    public void setId(long id) {
        this.id = id;
    }

    /**
     * Get the time that this persistent instance was first persisted.
     */
    @Column(nullable = false)
    @NotNull
    public LocalDateTime getCreateTime() {
        return this.createTime;
    }
    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }

    /**
     * Get the time that this persistent instance was most recently updated.
     */
    @Column(nullable = false)
    @NotNull
    public LocalDateTime getUpdateTime() {
        return this.updateTime;
    }
    public void setUpdateTime(LocalDateTime updateTime) {
        this.updateTime = updateTime;
    }

// Object

    @Override
    public String toString() {
        return this.getClass().getSimpleName() + "#" + this.getId();
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this)
            return true;
        if (obj == null || obj.getClass() != this.getClass())
            return false;
        final AbstractPersistent that = (AbstractPersistent)obj;
        return this.id != 0 && this.id == that.id;
    }

    @Override
    public int hashCode() {
        return this.getClass().hashCode() ^ Long.hashCode(this.id);
    }

// Change management and notifications

    @PrePersist
    public void prePersist() {

        // Set create time
        final LocalDateTime now = LocalDateTime.now();
        this.createTime = now;
        this.updateTime = now;
    }

    @PreRemove
    @PreUpdate
    public void preUpdate() {

        // Set update time
        this.updateTime = LocalDateTime.now();
    }
}
