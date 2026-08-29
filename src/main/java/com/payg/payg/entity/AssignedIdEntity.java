package com.payg.payg.entity;

import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

import java.util.UUID;

/**
 * Base for entities whose id is assigned by the application rather than the
 * database.
 *
 * <p>With an assigned id, Spring Data cannot tell a new row from a detached
 * one; {@link Persistable} tells it explicitly. Without this, {@code save}
 * would issue a SELECT and then an UPDATE, and the unique constraints we rely
 * on for idempotency would never be exercised.
 *
 * <p>The flag lives here rather than on the entities so that Lombok's
 * {@code @AllArgsConstructor} does not pick it up - it only generates
 * parameters for fields declared in the annotated class itself.
 */
@MappedSuperclass
public abstract class AssignedIdEntity implements Persistable<UUID> {

    @Transient
    private boolean isNew = true;

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostPersist
    @PostLoad
    void markNotNew() {
        this.isNew = false;
    }
}
