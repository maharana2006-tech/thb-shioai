package com.multiship.backend.controller;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.service.carriers.StampsPostageService;
import com.multiship.backend.service.carriers.StampsPostageService.AddFundsResult;
import com.multiship.backend.service.carriers.StampsPostageService.Balance;
import com.multiship.backend.service.carriers.StampsPostageService.FundingRow;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** USPS postage on Stamps.com: balance, add funds, history, and links to Stamps.com pages. ADMIN only. */
@Tag(name = "Stamps postage", description = "Postage balance and top-up for USPS accounts on Stamps.com")
@RestController
@RequestMapping("/api/v1/carrier-accounts/stamps-sera")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class StampsPostageController {

    private final StampsPostageService postage;

    /** {@code requestKey}: a v4 UUID made once per confirmed purchase, so a retry can't charge twice. */
    public record AddFundsRequest(BigDecimal amount, String requestKey) {}

    @Operation(summary = "Postage balance on Stamps.com")
    @GetMapping("/balance/{accountId}")
    public ResponseEntity<ApiResponse<Balance>> balance(@PathVariable Long accountId) {
        return ok("Balance read.", postage.balance(accountId));
    }

    @Operation(summary = "Add postage funds",
            description = "Charges the payment method saved on the Stamps.com account. Minimum $10; "
                    + "must stay within Stamps.com's balance limit.")
    @PostMapping("/add-funds/{accountId}")
    public ResponseEntity<ApiResponse<AddFundsResult>> addFunds(@PathVariable Long accountId,
                                                                @RequestBody AddFundsRequest req,
                                                                @AuthenticationPrincipal UserDetails user) {
        AddFundsResult result = postage.addFunds(accountId, req == null ? null : req.amount(),
                req == null ? null : req.requestKey(), user == null ? null : user.getUsername());
        return ok("Funds added.", result);
    }

    @Operation(summary = "Recent top-ups of an account (newest first, up to 20)")
    @GetMapping("/funding/{accountId}")
    public ResponseEntity<ApiResponse<List<FundingRow>>> funding(@PathVariable Long accountId) {
        return ok("Funding history.", postage.history(accountId));
    }

    @Operation(summary = "One-use link to a Stamps.com page",
            description = "page = store_auto_buy (auto-funding), store_history or store_payment_methods.")
    @GetMapping("/page-url/{accountId}")
    public ResponseEntity<ApiResponse<Map<String, String>>> pageUrl(@PathVariable Long accountId,
                                                                    @RequestParam String page) {
        return ok("Link ready.", Map.of("url", postage.pageUrl(accountId, page)));
    }

    private static <T> ResponseEntity<ApiResponse<T>> ok(String message, T data) {
        return ResponseEntity.ok(ApiResponse.<T>builder().status("success").code(200).message(message).data(data).build());
    }
}
