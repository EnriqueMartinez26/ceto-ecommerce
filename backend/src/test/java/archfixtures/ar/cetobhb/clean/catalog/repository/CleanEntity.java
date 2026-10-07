package archfixtures.ar.cetobhb.clean.catalog.repository;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;

/** Compliant fixture: an entity that changes state only through an intent method. */
@Entity
public class CleanEntity {

    @Id
    private Long id;

    private boolean active;

    public void deactivate() {
        active = false;
    }

    public boolean active() {
        return active;
    }
}
