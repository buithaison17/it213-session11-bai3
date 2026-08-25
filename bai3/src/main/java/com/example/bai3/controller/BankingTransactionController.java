package com.example.bai3.controller;

import com.example.bai3.service.BankingTransactionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/banking")
public class BankingTransactionController {

    private static final Logger log = LoggerFactory.getLogger(BankingTransactionController.class);
    private final BankingTransactionService bankingService;

    public BankingTransactionController(BankingTransactionService bankingService) {
        this.bankingService = bankingService;
    }

    @PostMapping("/transfer")
    public ResponseEntity<Map<String, Object>> transfer(
            @RequestParam(defaultValue = "ACC_1900123") String fromAccount,
            @RequestParam(defaultValue = "ACC_8800999") String toAccount,
            @RequestParam(defaultValue = "500000") BigDecimal amount) {

        log.info("[Controller] Tiếp nhận yêu cầu chuyển khoản từ: {} đến: {} với số tiền: {}", fromAccount, toAccount, amount);

        Map<String, Object> result = bankingService.processPayment(fromAccount, toAccount, amount);

        log.info("[Controller] Giao dịch xử lý thành công. Chuẩn bị gửi phản hồi client.");
        return ResponseEntity.ok(result);
    }
}