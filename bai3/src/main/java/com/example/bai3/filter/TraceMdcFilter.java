package com.example.bai3.filter;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceMdcFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(TraceMdcFilter.class);
    private static final String MDC_TRACE_ID_KEY = "trace_id";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            // 1. Trích xuất traceId từ OpenTelemetry context hiện tại
            SpanContext spanContext = Span.current().getSpanContext();
            String traceId = spanContext.isValid() ? spanContext.getTraceId() : null;

            // Fallback: nếu span context chưa sẵn sàng hoặc không hợp lệ, sinh UUID ngắn
            if (traceId == null || traceId.isEmpty() || "00000000000000000000000000000000".equals(traceId)) {
                traceId = UUID.randomUUID().toString().replace("-", "");
            }

            // 2. Nạp trace_id vào MDC của thread hiện tại
            MDC.put(MDC_TRACE_ID_KEY, traceId);

            // Gắn trace_id vào response header để tiện đối soát client
            response.setHeader("X-Trace-Id", traceId);

            log.info("[Filter] Inbound HTTP request: {} {}", request.getMethod(), request.getRequestURI());

            // 3. Cho request đi tiếp qua chuỗi filter và servlet
            filterChain.doFilter(request, response);

            log.info("[Filter] Outbound HTTP response status: {}", response.getStatus());
        } finally {
            // 4. BẮT BUỘC: Xóa sạch MDC sau khi kết thúc request để ngăn ngừa memory leak & data poisoning
            MDC.remove(MDC_TRACE_ID_KEY);
            MDC.clear();
        }
    }
}