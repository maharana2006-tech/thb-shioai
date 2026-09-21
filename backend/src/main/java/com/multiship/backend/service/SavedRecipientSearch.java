package com.multiship.backend.service;

import com.multiship.backend.model.SavedRecipient;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * How the address book is searched — by anything an operator remembers.
 *
 * <p>The text is split into words, and every word has to appear somewhere in
 * the entry: name, company, any street line, city, state, postal code,
 * country, phone, email or tag. So "wacker chicago", "chicago 60606", "suite
 * 400" and "jane 312" all find Jane Carter at 233 S Wacker Dr, Suite 400,
 * Chicago 60606. It used to need the whole text inside ONE of five fields, so
 * any two-part search, a second street line or a phone number found nothing.
 *
 * <p>Postal codes also match without their spaces ("sw1a1aa" finds SW1A 1AA),
 * and phone numbers on their digits alone ("312-555" finds 3125550101).
 */
final class SavedRecipientSearch {

    private SavedRecipientSearch() { }

    /** More words than this adds nothing but query cost. */
    static final int MAX_WORDS = 6;

    /** The search text as the words that must each match. */
    static List<String> words(String q) {
        if (!StringUtils.hasText(q)) return List.of();
        return Arrays.stream(q.trim().toLowerCase(Locale.ROOT).split("[\\s,;]+"))
                .map(String::trim)
                .filter(w -> !w.isEmpty())
                .distinct()
                .limit(MAX_WORDS)
                .toList();
    }

    /**
     * Whose entries are visible: every entry ({@code all}), only the shared
     * ones ({@code owner} null), or one client's plus the shared ones.
     */
    static Specification<SavedRecipient> visibleTo(boolean all, String owner) {
        return (root, query, cb) -> {
            if (all) return cb.conjunction();
            if (owner == null) return cb.isNull(root.get("ownerCustomerNo"));
            return cb.or(cb.equal(root.get("ownerCustomerNo"), owner), cb.isNull(root.get("ownerCustomerNo")));
        };
    }

    /** Every word of {@code q} appears somewhere in the entry. */
    static Specification<SavedRecipient> matching(String q) {
        List<String> words = words(q);
        return (root, query, cb) -> {
            if (words.isEmpty()) return cb.conjunction();
            List<Predicate> all = new ArrayList<>();
            for (String word : words) all.add(anyField(root, cb, word));
            return cb.and(all.toArray(Predicate[]::new));
        };
    }

    private static final String[] TEXT_FIELDS = {
            "name", "company", "addressLine1", "addressLine2", "addressLine3", "city", "state",
            "postalCode", "countryCode", "email", "tag"
    };

    private static Predicate anyField(Root<SavedRecipient> root, CriteriaBuilder cb, String word) {
        String like = "%" + escape(word) + "%";
        List<Predicate> any = new ArrayList<>();
        for (String field : TEXT_FIELDS) {
            any.add(cb.like(cb.lower(cb.coalesce(root.get(field), "")), like, '\\'));
        }
        // "sw1a1aa" finds "SW1A 1AA": compare postal codes with the spaces out.
        String compact = word.replace(" ", "");
        Expression<String> postal = cb.function("replace", String.class,
                cb.lower(cb.coalesce(root.get("postalCode"), "")), cb.literal(" "), cb.literal(""));
        any.add(cb.like(postal, "%" + escape(compact) + "%", '\\'));
        // "312-555" finds 3125550101: phones compared on their digits alone.
        String digits = word.replaceAll("\\D", "");
        if (digits.length() >= 3) {
            Expression<String> phoneDigits = cb.function("regexp_replace", String.class,
                    cb.coalesce(root.get("phone"), ""), cb.literal("[^0-9]"), cb.literal(""), cb.literal("g"));
            any.add(cb.like(phoneDigits, "%" + digits + "%"));
        }
        return cb.or(any.toArray(Predicate[]::new));
    }

    /** % and _ typed by the operator are literal characters, not wildcards. */
    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
