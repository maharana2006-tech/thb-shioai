package com.multiship.backend.repository;

import com.multiship.backend.model.UspsDirectSubscription;
import com.multiship.backend.model.UspsDirectSubscription.Status;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Data-access for {@link UspsDirectSubscription}. Rows are soft-deleted
 * (status flip), so most business reads should filter to
 * {@link Status#ACTIVE}.
 *
 * <p>Status parameters are the {@link Status} enum (matches the entity's
 * {@code @Enumerated(EnumType.STRING)} declaration — Hibernate rejects a
 * raw {@code String} in the parameter binder with
 * {@code QueryArgumentException}). String-flavoured overloads are
 * provided as {@code default} methods for callers that carry the value
 * in a JSON-y wire form.
 */
@Repository
public interface UspsDirectSubscriptionRepository
        extends JpaRepository<UspsDirectSubscription, Long> {

    /** Look up by USPS' own subscription id — used to prevent duplicate
     *  creates + to resolve the HMAC secret for a webhook verification. */
    Optional<UspsDirectSubscription> findByUspsSubscriptionId(String uspsSubscriptionId);

    /** Filter-based lookup — the webhook verification path uses this to
     *  find the row that owns the HMAC secret for an incoming event. */
    List<UspsDirectSubscription> findByFilterTypeAndFilterValueAndStatus(
            String filterType, String filterValue, Status status);

    /** List all rows in a given status — the {@code GET /subscriptions}
     *  admin call uses {@link Status#ACTIVE}. */
    List<UspsDirectSubscription> findByStatus(Status status);

    // --- String-flavoured convenience overloads ---

    /** Wrapper that parses the wire-format string to the enum. Has a
     *  body so Spring Data doesn't try to derive a second query. */
    default List<UspsDirectSubscription> findByFilterTypeAndFilterValueAndStatus(
            String filterType, String filterValue, String status) {
        return findByFilterTypeAndFilterValueAndStatus(filterType, filterValue, Status.valueOf(status));
    }

    default List<UspsDirectSubscription> findByStatus(String status) {
        return findByStatus(Status.valueOf(status));
    }
}
