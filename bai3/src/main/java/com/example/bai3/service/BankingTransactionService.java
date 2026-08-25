package com.example.bai3.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Map;

@Service
public class BankingTransactionService {

    private static final Logger log = LoggerFactory.getLogger(BankingTransactionService.class);

    public Map<String, Object> processPayment(String accountFrom, String accountTo, BigDecimal amount) {
        log.info("[Service] Bắt đầu xác thực tài khoản nguồn: {} và đích: {}", accountFrom, accountTo);
        log.debug("[Service] Kiểm tra số dư khả dụng cho giao dịch số tiền: {}", amount);

        // Giả lập logic kiểm tra nghiệp vụ ngân hàng
        log.info("[Service] Xác thực giao dịch thành công. Trừ tiền và cập nhật số dư sổ cái.");

        return Map.of(
                "status", "SUCCESS",
                "transactionId", "TXN_" + System.currentTimeMillis(),
                "accountFrom", accountFrom,
                "accountTo", accountTo,
                "amount", amount
        );
    }
}