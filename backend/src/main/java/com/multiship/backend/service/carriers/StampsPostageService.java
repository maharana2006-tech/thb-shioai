package com.multiship.backend.service.carriers;

import com.multiship.backend.model.CarrierAccountRef;
import com.multiship.backend.model.StampsFunding;
import com.multiship.backend.repository.CarrierAccountRefRepository;
import com.multiship.backend.repository.StampsFundingRepository;
import com.multiship.backend.service.AuditService;
import com.multiship.backend.service.carriers.CarrierConnector.BalanceResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Postage balance and manual top-up for USPS accounts on Stamps.com (SERA).
 * Stamps.com charges the payment method saved on its own account; this app
 * never sees card details.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StampsPostageService {

    /** Stamps.com's smallest postage purchase. */
    static final BigDecimal MIN_TOPUP = new BigDecimal("10");

    /**
     * Stamps.com pages the Carriers screen links to (SERA {@code url_type}s):
     * Payment settings (which card pays for postage, with a link to manage
     * cards) and the balance report. Stamps.com retired {@code store_auto_buy}
     * ("Invalid URLType"), so there is no auto-funding page to link to.
     */
    static final Set<String> PAGES = Set.of("store_default_payment_methods", "reports_balances");

    private final CarrierAccountRefRepository accounts;
    private final StampsFundingRepository fundings;
    private final StampsSeraOAuthService oauthService;
    private final StampsConnector stampsConnector;
    private final AuditService auditService;

    public record Balance(BigDecimal available, BigDecimal maxBalance, String currency) {}

    public record FundingRow(Long id, BigDecimal amount, String currency, String status,
                             BigDecimal balanceAfter, String message, String createdBy,
                             LocalDateTime createdAt) {}

    public record AddFundsResult(FundingRow funding, Balance balance) {}

    public Balance balance(long accountId) {
        CarrierAccountRef account = account(accountId);
        BalanceResult r = stampsConnector.getAccountBalance(accessToken(account), account.getEnvironment());
        if (!"OK".equals(r.status())) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Couldn't read the postage balance from Stamps.com: " + plain(r.message()));
        }
        return new Balance(r.amountAvailable(), r.maxBalance(), r.currency());
    }

    /**
     * Adds postage. {@code requestKey} is a v4 UUID the screen makes once per
     * confirmed purchase: Stamps.com replays the first answer for a repeated
     * key, and a key already recorded here is answered from the history
     * without calling Stamps.com again (double-click, retry after a timeout).
     */
    public AddFundsResult addFunds(long accountId, BigDecimal amount, String requestKey, String user) {
        String key = requestKey(requestKey);
        StampsFunding existing = fundings.findByIdempotencyKey(key).orElse(null);
        // Already settled under this key — replay the first answer, never re-charge.
        if (existing != null && !StampsFunding.PENDING.equals(existing.getStatus())) {
            return replay(existing);
        }

        // When recovering a PENDING reservation (a prior call that didn't return),
        // the reserved amount is authoritative; otherwise use the caller's.
        BigDecimal amt = existing != null ? existing.getAmount() : amount;
        if (amt == null || amt.compareTo(MIN_TOPUP) < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Add at least " + money(MIN_TOPUP) + ".");
        }
        if (amt.stripTrailingZeros().scale() > 2) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Enter dollars and cents, like 25.50.");
        }
        CarrierAccountRef account = account(accountId);
        String token = accessToken(account);

        // Balance-cap guard only for a brand-new purchase. When recovering a
        // PENDING row the charge may already be in flight at Stamps.com, so we
        // must call through (idempotently) rather than block on a stale cap read.
        if (existing == null) {
            BalanceResult now = stampsConnector.getAccountBalance(token, account.getEnvironment());
            if ("OK".equals(now.status()) && now.maxBalance() != null && now.amountAvailable() != null) {
                BigDecimal room = now.maxBalance().subtract(now.amountAvailable());
                if (room.compareTo(MIN_TOPUP) < 0) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The balance is already at Stamps.com's limit of "
                            + money(now.maxBalance()) + ". Ask Stamps.com to raise it to add more.");
                }
                if (amt.compareTo(room) > 0) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "That would go over Stamps.com's balance limit of "
                            + money(now.maxBalance()) + ". You can add up to " + money(room) + ".");
                }
            }
        }

        // Reserve: record the key as PENDING *before* charging, so a crash
        // between the charge and the write still leaves a trace and the UNIQUE
        // key blocks a duplicate charge on resubmit. Committed in its own tx.
        StampsFunding row = existing;
        if (row == null) {
            try {
                row = fundings.save(StampsFunding.builder()
                        .carrierAccountRefId(accountId).amount(amt).currency("USD")
                        .status(StampsFunding.PENDING).idempotencyKey(key)
                        .createdBy(user).createdAt(LocalDateTime.now()).build());
            } catch (DataIntegrityViolationException raced) {
                // Another request reserved the same key first.
                StampsFunding other = fundings.findByIdempotencyKey(key).orElseThrow(() -> raced);
                if (!StampsFunding.PENDING.equals(other.getStatus())) return replay(other);
                row = other; // both settle idempotently against Stamps below
            }
        }

        // Execute. Stamps.com is idempotent on the key, so re-calling after a
        // crash replays the original charge instead of charging twice.
        BalanceResult r = stampsConnector.addFundsSera(token, amt, "usd", key, account.getEnvironment());
        boolean ok = "OK".equals(r.status());

        // Settle the reserved row.
        row.setStatus(ok ? StampsFunding.ADDED : StampsFunding.FAILED);
        row.setBalanceAfter(ok ? r.amountAvailable() : null);
        row.setMessage(ok ? null : plain(r.message()));
        row = fundings.save(row);

        auditService.record(AuditService.UPDATE, AuditService.CARRIER_ACCOUNT, accountId,
                account.getCarrierCode() + " · " + account.getAccountNumber(),
                Map.of("amount", amt, "status", row.getStatus()),
                ok ? "Added " + money(amt) + " postage on Stamps.com"
                        : "Stamps.com refused a " + money(amt) + " top-up: " + row.getMessage());
        log.info("Stamps.com add-funds: account #{} +{} → {}", accountId, amt, row.getStatus());
        if (!ok) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Stamps.com didn't add the funds: " + row.getMessage());
        }
        return new AddFundsResult(toRow(row), new Balance(r.amountAvailable(), r.maxBalance(), r.currency()));
    }

    public List<FundingRow> history(long accountId) {
        return fundings.findTop20ByCarrierAccountRefIdOrderByCreatedAtDesc(accountId).stream()
                .map(StampsPostageService::toRow).toList();
    }

    /** A one-use link to a Stamps.com page (payment settings, balance report). */
    public String pageUrl(long accountId, String page) {
        if (!PAGES.contains(page)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown Stamps.com page: " + page);
        }
        CarrierAccountRef account = account(accountId);
        try {
            return stampsConnector.getHostedUrlSera(accessToken(account), page, account.getEnvironment());
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Couldn't open Stamps.com: " + ex.getMessage());
        }
    }

    private AddFundsResult replay(StampsFunding earlier) {
        if (StampsFunding.FAILED.equals(earlier.getStatus())) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Stamps.com didn't add the funds: " + earlier.getMessage());
        }
        return new AddFundsResult(toRow(earlier), null);
    }

    private CarrierAccountRef account(long accountId) {
        CarrierAccountRef account = accounts.findById(accountId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Carrier account " + accountId + " was not found."));
        if (!"USPS".equalsIgnoreCase(account.getCarrierCode())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Postage top-up is only for USPS accounts.");
        }
        if (!StringUtils.hasText(account.getStampsRefreshToken())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This USPS account isn't authorized with "
                    + "Stamps.com yet. Click \"Authorize with Stamps.com\" first.");
        }
        return account;
    }

    private String accessToken(CarrierAccountRef account) {
        StampsSeraOAuthService.TokenExchangeResult t = oauthService.refreshToken(account.getStampsRefreshToken(),
                account.getClientId(), account.getClientSecret(), account.getEnvironment());
        if (!t.success()) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Stamps.com didn't accept this account's "
                    + "authorization (" + t.errorMessage() + "). Click \"Authorize with Stamps.com\" again.");
        }
        return t.accessToken();
    }

    /** The screen's key when it is a v4 UUID (what Stamps.com requires); otherwise a fresh one. */
    static String requestKey(String requestKey) {
        if (StringUtils.hasText(requestKey)) {
            try {
                UUID u = UUID.fromString(requestKey.trim());
                if (u.version() == 4) return u.toString();
            } catch (IllegalArgumentException ignored) {
                // fall through to a fresh key
            }
        }
        return UUID.randomUUID().toString();
    }

    /** "SERA add-funds rejected (HTTP 400): Card declined" → "Card declined". */
    static String plain(String message) {
        if (!StringUtils.hasText(message)) return "no reason given";
        return message.replaceFirst("^SERA [^:]*:\\s*", "");
    }

    private static String money(BigDecimal amount) {
        return NumberFormat.getCurrencyInstance(Locale.US).format(amount);
    }

    private static FundingRow toRow(StampsFunding f) {
        return new FundingRow(f.getId(), f.getAmount(), f.getCurrency(), f.getStatus(), f.getBalanceAfter(),
                f.getMessage(), f.getCreatedBy(), f.getCreatedAt());
    }
}
