package archfixtures.ar.cetobhb.entitysetter.repository;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;

/** Violating fixture: an entity that exposes a public status setter. */
@Entity
public class SettableOrder {

    @Id
    private Long id;

    private String status;

    public void setStatus(String status) {
        this.status = status;
    }

    void setPackagePrivateIsFine(String value) {
        this.status = value;
    }
}
