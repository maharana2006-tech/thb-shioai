package com.multiship.backend.model;

import com.multiship.backend.service.ImportRevalidator;
import jakarta.persistence.PostPersist;
import jakarta.persistence.PostRemove;
import jakarta.persistence.PostUpdate;

/** A client, account, client–warehouse link or ship via mapping changed: its saved imports are checked again. */
public class ImportRevalidationListener {

    @PostPersist
    @PostUpdate
    @PostRemove
    public void changed(Object entity) {
        String client = switch (entity) {
            case Client c -> c.getClientCode();
            case CarrierAccountRef a -> a.getCustomerNo();   // blank: a platform account, usable by every client
            case ClientWarehouse w -> w.getClientCode();
            case ShipViaMapping m -> m.getClientCode();      // blank: a rule for every client
            default -> null;
        };
        ImportRevalidator.settingsChanged(client);
    }
}
