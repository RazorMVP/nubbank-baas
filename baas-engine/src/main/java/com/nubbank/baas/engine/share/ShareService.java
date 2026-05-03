package com.nubbank.baas.engine.share;

import com.nubbank.baas.engine.common.BaasException;
import com.nubbank.baas.engine.customer.CustomerRepository;
import com.nubbank.baas.engine.share.dto.*;
import com.nubbank.baas.engine.tenant.PartnerContext;
import com.nubbank.baas.engine.virtualaccount.VirtualAccountService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ShareService {

    private final ShareProductRepository productRepo;
    private final ShareAccountRepository accountRepo;
    private final ShareTransactionRepository txRepo;
    private final CustomerRepository customerRepo;
    private final VirtualAccountService virtualAccountService;

    @Transactional
    public ShareProductResponse createProduct(ShareProductRequest req) {
        requireContext();
        if (productRepo.findByShortName(req.shortName()).isPresent())
            throw BaasException.conflict("DUPLICATE_SHORT_NAME", "Short name in use");
        ShareProduct product = ShareProduct.builder()
            .name(req.name())
            .shortName(req.shortName())
            .description(req.description())
            .totalShares(req.totalShares())
            .sharesIssued(0L)
            .unitPrice(req.unitPrice())
            .minimumShares(req.minimumShares() != null ? req.minimumShares() : 1)
            .maximumShares(req.maximumShares())
            .currencyCode(req.currencyCode() != null ? req.currencyCode() : "NGN")
            .build();
        return toProductResponse(productRepo.save(product));
    }

    @Transactional(readOnly = true)
    public ShareProductResponse getProduct(UUID id) {
        requireContext();
        return toProductResponse(productRepo.findById(id)
            .orElseThrow(() -> BaasException.notFound("PRODUCT_NOT_FOUND", "Share product not found")));
    }

    @Transactional
    public ShareAccountResponse openAccount(ShareAccountRequest req) {
        requireContext();
        var customer = customerRepo.findById(req.customerId())
            .orElseThrow(() -> BaasException.notFound("CUSTOMER_NOT_FOUND", "Customer not found"));
        var product = productRepo.findById(req.productId())
            .orElseThrow(() -> BaasException.notFound("PRODUCT_NOT_FOUND", "Share product not found"));
        String accountNumber = virtualAccountService.assignNext(PartnerContext.get().schemaName());
        ShareAccount account = ShareAccount.builder()
            .customer(customer)
            .product(product)
            .accountNumber(accountNumber)
            .totalSharesHeld(0L)
            .totalAmount(BigDecimal.ZERO)
            .currencyCode(product.getCurrencyCode())
            .build();
        return toAccountResponse(accountRepo.save(account));
    }

    @Transactional
    public ShareAccountResponse executeCommand(UUID id, String command) {
        requireContext();
        var account = accountRepo.findById(id)
            .orElseThrow(() -> BaasException.notFound("ACCOUNT_NOT_FOUND", "Share account not found"));
        switch (command.toLowerCase()) {
            case "approve" -> {
                if (account.getStatus() != ShareAccountStatus.SUBMITTED)
                    throw BaasException.badRequest("INVALID_STATUS", "Only SUBMITTED accounts can be approved");
                account.setStatus(ShareAccountStatus.APPROVED);
            }
            case "activate" -> {
                if (account.getStatus() != ShareAccountStatus.APPROVED)
                    throw BaasException.badRequest("INVALID_STATUS", "Only APPROVED accounts can be activated");
                account.setStatus(ShareAccountStatus.ACTIVE);
            }
            case "reject" -> {
                if (account.getStatus() != ShareAccountStatus.SUBMITTED)
                    throw BaasException.badRequest("INVALID_STATUS", "Only SUBMITTED accounts can be rejected");
                account.setStatus(ShareAccountStatus.REJECTED);
            }
            case "close" -> {
                if (account.getStatus() != ShareAccountStatus.ACTIVE)
                    throw BaasException.badRequest("INVALID_STATUS", "Only ACTIVE accounts can be closed");
                if (account.getTotalSharesHeld() > 0)
                    throw BaasException.badRequest("SHARES_OUTSTANDING", "Redeem all shares before closing");
                account.setStatus(ShareAccountStatus.CLOSED);
            }
            default -> throw BaasException.badRequest("UNKNOWN_COMMAND", "Unknown command: " + command);
        }
        return toAccountResponse(accountRepo.save(account));
    }

    @Transactional
    public ShareTransactionResponse purchaseShares(UUID accountId, ShareTransactionRequest req) {
        requireContext();
        var account = accountRepo.findById(accountId)
            .orElseThrow(() -> BaasException.notFound("ACCOUNT_NOT_FOUND", "Share account not found"));
        if (account.getStatus() != ShareAccountStatus.ACTIVE)
            throw BaasException.badRequest("ACCOUNT_NOT_ACTIVE", "Account must be ACTIVE to purchase shares");
        ShareProduct product = account.getProduct();
        long available = product.getTotalShares() - product.getSharesIssued();
        if (req.numberOfShares() > available)
            throw BaasException.badRequest("INSUFFICIENT_SHARES", "Only " + available + " shares available");
        if (req.numberOfShares() < product.getMinimumShares())
            throw BaasException.badRequest("BELOW_MINIMUM", "Minimum purchase is " + product.getMinimumShares() + " shares");

        product.setSharesIssued(product.getSharesIssued() + req.numberOfShares());
        productRepo.save(product);
        account.setTotalSharesHeld(account.getTotalSharesHeld() + req.numberOfShares());
        account.setTotalAmount(account.getTotalAmount().add(
            product.getUnitPrice().multiply(BigDecimal.valueOf(req.numberOfShares()))));
        accountRepo.save(account);

        ShareTransaction tx = txRepo.save(ShareTransaction.builder()
            .account(account)
            .transactionType(ShareTransactionType.PURCHASE)
            .numberOfShares(req.numberOfShares())
            .unitPrice(product.getUnitPrice())
            .build());
        return toTxResponse(tx, accountId);
    }

    @Transactional
    public ShareTransactionResponse redeemShares(UUID accountId, ShareTransactionRequest req) {
        requireContext();
        var account = accountRepo.findById(accountId)
            .orElseThrow(() -> BaasException.notFound("ACCOUNT_NOT_FOUND", "Share account not found"));
        if (account.getStatus() != ShareAccountStatus.ACTIVE)
            throw BaasException.badRequest("ACCOUNT_NOT_ACTIVE", "Account must be ACTIVE to redeem shares");
        if (req.numberOfShares() > account.getTotalSharesHeld())
            throw BaasException.badRequest("INSUFFICIENT_SHARES", "Not enough shares to redeem");
        ShareProduct product = account.getProduct();
        product.setSharesIssued(product.getSharesIssued() - req.numberOfShares());
        productRepo.save(product);
        account.setTotalSharesHeld(account.getTotalSharesHeld() - req.numberOfShares());
        account.setTotalAmount(account.getTotalAmount().subtract(
            product.getUnitPrice().multiply(BigDecimal.valueOf(req.numberOfShares()))));
        accountRepo.save(account);

        ShareTransaction tx = txRepo.save(ShareTransaction.builder()
            .account(account)
            .transactionType(ShareTransactionType.REDEEM)
            .numberOfShares(req.numberOfShares())
            .unitPrice(product.getUnitPrice())
            .build());
        return toTxResponse(tx, accountId);
    }

    @Transactional(readOnly = true)
    public ShareAccountResponse getAccount(UUID id) {
        requireContext();
        return toAccountResponse(accountRepo.findById(id)
            .orElseThrow(() -> BaasException.notFound("ACCOUNT_NOT_FOUND", "Share account not found")));
    }

    @Transactional(readOnly = true)
    public Page<ShareAccountResponse> listByCustomer(UUID customerId, int page, int size) {
        requireContext();
        return accountRepo.findByCustomerId(customerId, PageRequest.of(page, size))
            .map(this::toAccountResponse);
    }

    @Transactional(readOnly = true)
    public Page<ShareTransactionResponse> listTransactions(UUID accountId, int page, int size) {
        requireContext();
        return txRepo.findByAccountIdOrderByCreatedAtDesc(accountId, PageRequest.of(page, size))
            .map(tx -> toTxResponse(tx, accountId));
    }

    private void requireContext() {
        if (PartnerContext.get() == null)
            throw BaasException.unauthorized("MISSING_AUTH", "Authorization required");
    }

    private ShareTransactionResponse toTxResponse(ShareTransaction tx, UUID accountId) {
        return new ShareTransactionResponse(
            tx.getId(), accountId, tx.getTransactionType(),
            tx.getNumberOfShares(), tx.getUnitPrice(), tx.getTotalAmount(), tx.getCreatedAt());
    }

    private ShareProductResponse toProductResponse(ShareProduct p) {
        return new ShareProductResponse(
            p.getId(), p.getName(), p.getShortName(),
            p.getTotalShares(), p.getSharesIssued(), p.getUnitPrice(),
            p.getMinimumShares(), p.getMaximumShares(), p.getCurrencyCode(),
            p.isActive(), p.getCreatedAt());
    }

    private ShareAccountResponse toAccountResponse(ShareAccount a) {
        return new ShareAccountResponse(
            a.getId(), a.getCustomer().getId(), a.getProduct().getId(),
            a.getAccountNumber(), a.getTotalSharesHeld(), a.getTotalAmount(),
            a.getStatus(), a.getCurrencyCode(), a.getCreatedAt());
    }
}
