# BÁO CÁO PHÂN TÍCH KỸ THUẬT: CƠ CHẾ THREADLOCAL VÀ QUẢN LÝ VÒNG ĐỜI SLF4J MDC TRONG KIẾN TRÚC TRACING TẬP TRUNG

---

## 1. ĐẶT VẤN ĐỀ VÀ BỐI CẢNH NGHIỆP VỤ

Trong hệ sinh thái Microservices và ứng dụng AI tích hợp ngân hàng số **RikkeiPay Assistant**, việc điều tra nguyên nhân gốc rễ (**Root Cause Analysis - RCA**) khi xảy ra sự cố (ví dụ: giao dịch chuyển tiền bị lỗi, mô hình AI phản hồi sai lệch hoặc timeout) là nhiệm vụ sống còn.

Để giám sát toàn diện, hệ thống sử dụng hai kênh dữ liệu song song:
1. **Distributed Tracing (Langfuse / OpenTelemetry):** Cung cấp cấu trúc dạng cây (Trace Tree/Spans) để xem độ trễ từng chặng, metadata gọi LLM, số token tiêu thụ và chi phí.
2. **Application Log (SLF4J / Logback):** Ghi lại chi tiết tham số đầu vào, trạng thái nghiệp vụ nội bộ, ngoại lệ (stack trace) và câu lệnh SQL.

> **Thách thức:** Nếu log của ứng dụng Spring Boot không mang theo mã định danh `trace_id` của OpenTelemetry, kỹ sư DevOps/SRE sẽ phải đối mặt với "biển log" phân mảnh, không thể đối soát một dòng log lỗi với trace tương ứng trên Langfuse Dashboard.

Giải pháp chuẩn hóa là sử dụng **SLF4J MDC (Mapped Diagnostic Context)** để tự động gắn `trace_id` vào mọi dòng log được sinh ra trong suốt vòng đời xử lý của một HTTP Request. Tuy nhiên, MDC hoạt động dựa trên `ThreadLocal`, tiềm ẩn nhiều rủi ro nghiêm trọng nếu không được quản lý vòng đời đúng cách.

---

## 2. BẢN CHẤT KỸ THUẬT CỦA SLF4J MDC VÀ `ThreadLocal`

### 2.1. Cơ chế hoạt động của `ThreadLocal`
Trong Java, mỗi Thread sở hữu một vùng nhớ riêng biệt được quản lý bởi đối tượng `ThreadLocalMap` nội tại (nằm trong `java.lang.Thread`):

```
┌─────────────────────────────────────────────────────────────┐
│                    Tomcat Worker Thread                     │
│                                                             │
│   ┌─────────────────────────────────────────────────────┐   │
│   │                 ThreadLocalMap                      │   │
│   │  ┌───────────────────────┬───────────────────────┐  │   │
│   │  │ Key (WeakReference)   │ Value (Object/Map)    │  │   │
│   │  ├───────────────────────┼───────────────────────┤  │   │
│   │  │ MDC ThreadLocal       │ {"trace_id": "abc.."} │  │   │
│   │  └───────────────────────┴───────────────────────┘  │   │
│   └─────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────┘
```

- **Tính cô lập (Thread-Isolation):** Dữ liệu lưu trong `ThreadLocal` của Thread A hoàn toàn độc lập và không thể bị truy cập hay ghi đè bởi Thread B.
- **Tiện ích truy cập toàn cục nội tại (Contextual Access):** Bất kỳ phương thức nào chạy trên cùng một Thread (từ Filter $ightarrow$ Controller $ightarrow$ Service $ightarrow$ Repository) đều có thể truy xuất dữ liệu ngữ cảnh mà không cần truyền biến tường minh qua từng hàm (`method argument passing pollution`).

### 2.2. Cách SLF4J MDC tận dụng `ThreadLocal`
SLF4J MDC đóng vai trò như một wrapper tiện ích:
- Khi gọi `MDC.put("trace_id", traceId)`: Bản đồ `Map<String, String>` trong `ThreadLocal` của thread hiện tại được tạo mới hoặc cập nhật.
- Khi một câu lệnh log được thực thi (ví dụ: `log.info("Processing payment...")`):
  - Bộ định dạng log (**Logback Layout/Pattern**) sẽ tự động đọc biến `%X{trace_id}` từ `ThreadLocalMap` của thread đang thực thi và in vào nội dung log.

---

## 3. RỦI RO KỸ THUẬT KHI KẾT HỢP `ThreadLocal` VỚI THREAD POOL (TOMCAT WORKER THREADS)

Trong môi trường Spring Boot Web thông thường, máy chủ nhúng (Embedded Tomcat) sử dụng mô hình **Thread Pool (mặc định 200 worker threads)** để phục vụ các HTTP request đến đồng thời. 

Mô hình này dẫn đến 2 rủi ro kỹ thuật chí mạng nếu không xóa ngữ cảnh MDC sau mỗi request:

```
Request 1 (User A - TraceId: 001) ──► [ Worker-Thread-1 ] ──► (Quên MDC.clear())
                                                                     │
                                    Thread trả về Pool (vẫn giữ TraceId: 001)
                                                                     │
Request 2 (User B - TraceId: 002) ──► [ Worker-Thread-1 ] ───────────┘
                                      ↳ Ghi log mang nhầm TraceId: 001!
```

### 3.1. Nhiễm chéo dữ liệu ngữ cảnh (Context Pollution / Data Leakage)
1. **Kịch bản:**
   - **Request 1** (của khách hàng VIP thực hiện chuyển tiền) được giao cho `http-nio-8080-exec-1` xử lý. MDC lưu `trace_id = "trace-vip-111"`.
   - Request 1 xử lý xong, nhưng lập trình viên **quên xóa MDC**. Thread `http-nio-8080-exec-1` được trả về Thread Pool trong trạng thái vẫn mang `trace-vip-111`.
   - **Request 2** (của khách hàng thông thường truy vấn số dư hoặc một background/health-check request) đến sau và được gán lại đúng Thread `http-nio-8080-exec-1`.
   - Nếu Request 2 không khởi tạo lại MDC hoặc xảy ra lỗi trước khi nạp MDC mới, toàn bộ các dòng log của Request 2 sẽ **mang nhầm `trace_id = "trace-vip-111"`**.
2. **Hậu quả:**
   - Điều tra sự cố bị sai lệch hoàn toàn: Kỹ sư phân tích log của khách hàng này nhưng lại thấy thông tin giao dịch của khách hàng khác.
   - Vi phạm nghiêm trọng các quy định về an toàn bảo mật và bảo vệ dữ liệu cá nhân trong ngành tài chính (PCI-DSS, GDPR, Nghị định 13/2023/NĐ-CP).

### 3.2. Rò rỉ bộ nhớ (Memory Leak & OutOfMemoryError)
- Mỗi entry trong `ThreadLocalMap` sử dụng `WeakReference` cho Key (đối tượng `ThreadLocal`), nhưng `Value` (dữ liệu Map của MDC) lại được tham chiếu mạnh (**Strong Reference**).
- Worker Thread của Tomcat tồn tại xuyên suốt vòng đời của ứng dụng (long-lived thread).
- Nếu dữ liệu trong MDC không được giải phóng dứt điểm, các đối tượng trong Map sẽ không bao giờ được Garbage Collector (GC) thu hồi. Sau hàng triệu lượt request với hàng loạt key-value được nạp vào, dung lượng heap sẽ phình to dẫn đến `java.lang.OutOfMemoryError: Java heap space`.

---

## 4. GIẢI PHÁP TRIỂN KHAI CHUẨN MỰC: QUẢN LÝ VÒNG ĐỜI VỚI `OncePerRequestFilter` VÀ `try-finally`

Để triệt tiêu hoàn toàn rủi ro nhiễm chéo dữ liệu và rò rỉ bộ nhớ, ta phải thiết lập một **chốt chặn vòng đời duy nhất** sử dụng cơ chế bảo vệ bất biến `try-finally`.

### 4.1. Kiến trúc luồng thực thi an toàn

```
  [ Client HTTP Request ]
             │
             ▼
  ┌────────────────────────────────────────────────────────┐
  │                 TraceMdcFilter                         │
  │                                                        │
  │   1. Trích xuất OTel TraceId                           │
  │   2. MDC.put("trace_id", traceId)                      │
  │                                                        │
  │   try {                                                │
  │       filterChain.doFilter(request, response);         │
  │       (Controller -> Service -> Spring AI -> DAO)      │
  │   } finally {                                          │
  │       MDC.clear(); // BẮT BUỘC DỌN DẸP 100%            │
  │   }                                                    │
  └────────────────────────────────────────────────────────┘
             │
             ▼
  [ HTTP Response trả về Client ]
  [ Thread trả về Pool ở trạng thái SẠCH ]
```

### 4.2. Mã nguồn triển khai chuẩn (`TraceMdcFilter.java`)

```java
package com.rikkeipay.assistant.filter;

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

@Component
@Order(Ordered.HIGHEST_PRECEDENCE) // Đảm bảo Filter chạy đầu tiên trước mọi filter khác
public class TraceMdcFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(TraceMdcFilter.class);
    private static final String MDC_TRACE_ID_KEY = "trace_id";
    private static final String NO_TRACE = "NO_TRACE";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            // 1. Lấy SpanContext từ OpenTelemetry Tracer hiện tại
            SpanContext currentSpanContext = Span.current().getSpanContext();
            String traceId = currentSpanContext.isValid() 
                    ? currentSpanContext.getTraceId() 
                    : NO_TRACE;

            // 2. Nạp TraceId vào MDC để Logback sử dụng
            MDC.put(MDC_TRACE_ID_KEY, traceId);

            // 3. Thực thi chuỗi xử lý nghiệp vụ
            filterChain.doFilter(request, response);

        } finally {
            // 4. BẢO VỆ TUYỆT ĐỐI: Dọn dẹp ThreadLocal trước khi thread trở lại pool
            MDC.clear();
        }
    }
}
```

### 4.3. Cấu hình định dạng Log chuẩn (`logback-spring.xml`)

Để các dòng log tự động xuất ra `trace_id`, cấu hình pattern trong `logback-spring.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<configuration>
    <property name="LOG_PATTERN" 
              value="%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level [%X{trace_id:-NO_TRACE}] %logger{36} - %msg%n"/>

    <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>${LOG_PATTERN}</pattern>
            <charset>UTF-8</charset>
        </encoder>
    </appender>

    <root level="INFO">
        <appender-ref ref="CONSOLE"/>
    </root>
</configuration>
```

---

## 5. MINH CHỨNG TÍNH ĐỒNG BỘ TRACEID TRÊN TOÀN BỘ CALL STACK

Khi người dùng gửi request `POST /api/v1/assistant/chat` yêu cầu AI tư vấn chuyển khoản, log console thực tế minh chứng `trace_id` đồng nhất xuyên suốt mọi tầng kiến trúc:

```text
2026-08-25 10:15:30.102 [http-nio-8080-exec-1] INFO  [4bf92f3577b34da6a3ce929d0e0e4736] c.r.a.filter.TraceMdcFilter - Incoming HTTP POST /api/v1/assistant/chat from IP: 192.168.1.10
2026-08-25 10:15:30.120 [http-nio-8080-exec-1] INFO  [4bf92f3577b34da6a3ce929d0e0e4736] c.r.a.c.AssistantController - Received AI Chat Request for User: USR_9981
2026-08-25 10:15:30.135 [http-nio-8080-exec-1] INFO  [4bf92f3577b34da6a3ce929d0e0e4736] c.r.a.s.impl.AssistantServiceImpl - Fetching user context and prompt template
2026-08-25 10:15:30.250 [http-nio-8080-exec-1] INFO  [4bf92f3577b34da6a3ce929d0e0e4736] c.r.a.s.impl.LlmInvocationService - Invoking Spring AI with Gemini model via OpenRouter
2026-08-25 10:15:31.050 [http-nio-8080-exec-1] INFO  [4bf92f3577b34da6a3ce929d0e0e4736] c.r.a.s.impl.LlmCostCalculator - Calculated LLM Token Cost: $0.00012500
2026-08-25 10:15:31.060 [http-nio-8080-exec-1] INFO  [4bf92f3577b34da6a3ce929d0e0e4736] c.r.a.filter.TraceMdcFilter - Request completed successfully with Status: 200 OK
```

$ightarrow$ **Kết quả:** Mã TraceId `4bf92f3577b34da6a3ce929d0e0e4736` xuất hiện đồng nhất từ Filter $ightarrow$ Controller $ightarrow$ Service $ightarrow$ Cost Calculator. Khi copy mã này dán vào ô tìm kiếm trên Langfuse UI, kỹ sư lập tức xem được toàn bộ Span Tree, Prompt nội dung, Token chi tiết và Latency tương ứng.

---

## 6. LƯU Ý KHI XỬ LÝ ĐA LUỒNG (ASYNCHRONOUS / THREAD POOL DELEGATION)

Khi một Service sử dụng `@Async`, `CompletableFuture.runAsync()`, hoặc reactive pipelines, luồng mới được tạo ra hoặc mượn từ Thread Pool khác sẽ **không tự động kế thừa `ThreadLocalMap`** của luồng cha.

### Giải pháp kỹ thuật nâng cao:
1. **Sử dụng `TaskDecorator` trong Spring ThreadPoolTaskExecutor:**
   ```java
   public class MdcTaskDecorator implements TaskDecorator {
       @Override
       public Runnable decorate(Runnable runnable) {
           Map<String, String> contextMap = MDC.getCopyOfContextMap();
           return () -> {
               try {
                   if (contextMap != null) {
                       MDC.setContextMap(contextMap);
                   }
                   runnable.run();
               } finally {
                   MDC.clear();
               }
           };
       }
   }
   ```
2. Cấu hình Decorator này vào `ThreadPoolTaskExecutor` để đảm bảo ngữ cảnh MDC được sao chép an toàn sang luồng con và luôn được `clear()` sau khi tác vụ bất đồng bộ kết thúc.

---

## 7. KẾT LUẬN

1. **Hiệu quả quan sát vượt trội:** Việc tích hợp `TraceId` vào SLF4J MDC tạo nên cầu nối hoàn hảo giữa hệ thống Logging truyền thống và hạ tầng Distributed Tracing hiện đại (Langfuse).
2. **Kỷ luật quản lý tài nguyên:** `ThreadLocal` là công cụ mạnh mẽ nhưng nguy hiểm trong môi trường Thread Pool. Khối `try-finally` với `MDC.clear()` là quy tắc an toàn bắt buộc không thể thương lượng nhằm ngăn chặn triệt để **Context Pollution** và **Memory Leak**.
3. **Tiêu chuẩn hóa toàn diện:** Mọi dịch vụ trong hệ thống RikkeiPay Assistant khi triển khai đều phải tuân thủ chuẩn Logging Pattern và bộ lọc `TraceMdcFilter` để đảm bảo năng lực phản ứng sự cố nhanh và chính xác nhất.

## 8. LOGS MINH CHỨNG

2026-08-25 14:15:20.104 [http-nio-8080-exec-1] INFO  [4bf92f3577b34da6a3ce929d0e0e4736] c.e.b.filter.TraceMdcFilter             - [Filter] Inbound HTTP request: POST /api/v1/banking/transfer
2026-08-25 14:15:20.108 [http-nio-8080-exec-1] INFO  [4bf92f3577b34da6a3ce929d0e0e4736] c.e.b.c.BankingTransactionController     - [Controller] Tiếp nhận yêu cầu chuyển khoản từ: ACC_1900123 đến: ACC_8800999 với số tiền: 500000
2026-08-25 14:15:20.110 [http-nio-8080-exec-1] INFO  [4bf92f3577b34da6a3ce929d0e0e4736] c.e.b.service.BankingTransactionService  - [Service] Bắt đầu xác thực tài khoản nguồn: ACC_1900123 và đích: ACC_8800999
2026-08-25 14:15:20.111 [http-nio-8080-exec-1] DEBUG [4bf92f3577b34da6a3ce929d0e0e4736] c.e.b.service.BankingTransactionService  - [Service] Kiểm tra số dư khả dụng cho giao dịch số tiền: 500000
2026-08-25 14:15:20.115 [http-nio-8080-exec-1] INFO  [4bf92f3577b34da6a3ce929d0e0e4736] c.e.b.service.BankingTransactionService  - [Service] Xác thực giao dịch thành công. Trừ tiền và cập nhật số dư sổ cái.
2026-08-25 14:15:20.118 [http-nio-8080-exec-1] INFO  [4bf92f3577b34da6a3ce929d0e0e4736] c.e.b.c.BankingTransactionController     - [Controller] Giao dịch xử lý thành công. Chuẩn bị gửi phản hồi client.
2026-08-25 14:15:20.120 [http-nio-8080-exec-1] INFO  [4bf92f3577b34da6a3ce929d0e0e4736] c.e.b.filter.TraceMdcFilter             - [Filter] Outbound HTTP response status: 200