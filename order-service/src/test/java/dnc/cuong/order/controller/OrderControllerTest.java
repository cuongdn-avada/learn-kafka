package dnc.cuong.order.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import dnc.cuong.common.dto.OrderCreateRequest;
import dnc.cuong.common.event.OrderStatus;
import dnc.cuong.order.domain.Order;
import dnc.cuong.order.domain.OrderItem;
import dnc.cuong.order.service.OrderNotFoundException;
import dnc.cuong.order.service.OrderService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Controller test dùng @WebMvcTest — chỉ load web layer, mock service layer.
 *
 * WHY @WebMvcTest thay vì @SpringBootTest?
 * -> Chỉ load controller + Jackson config → test nhanh, không cần DB/Kafka.
 * -> Focus test: request mapping, request validation, response format, HTTP status codes.
 * -> @MockBean inject mock OrderService vào controller.
 */
@WebMvcTest(OrderController.class)
class OrderControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private OrderService orderService;

    // --- POST /api/orders ---

    @Test
    void createOrder_shouldReturn201WithOrderResponse() throws Exception {
        // Given
        UUID orderId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();

        Order order = buildOrder(orderId, customerId, OrderStatus.PLACED,
                new BigDecimal("2499.99"), null);
        OrderItem item = OrderItem.builder()
                .productId(productId).productName("MacBook Pro")
                .quantity(1).price(new BigDecimal("2499.99")).build();
        order.addItem(item);

        when(orderService.createOrder(any(OrderCreateRequest.class))).thenReturn(order);

        String requestBody = """
                {
                    "customerId": "%s",
                    "items": [
                        {
                            "productId": "%s",
                            "productName": "MacBook Pro",
                            "quantity": 1,
                            "price": 2499.99
                        }
                    ]
                }
                """.formatted(customerId, productId);

        // When / Then
        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.orderId").value(orderId.toString()))
                .andExpect(jsonPath("$.customerId").value(customerId.toString()))
                .andExpect(jsonPath("$.totalAmount").value(2499.99))
                .andExpect(jsonPath("$.status").value("PLACED"))
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].productName").value("MacBook Pro"));
    }

    // --- GET /api/orders/{orderId} ---

    @Test
    void getOrder_shouldReturn200WithOrderResponse() throws Exception {
        // Given
        UUID orderId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();

        Order order = buildOrder(orderId, customerId, OrderStatus.COMPLETED,
                new BigDecimal("500.00"), null);

        when(orderService.getOrder(orderId)).thenReturn(order);

        // When / Then
        mockMvc.perform(get("/api/orders/{orderId}", orderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value(orderId.toString()))
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    void getOrder_shouldReturn404_whenNotFound() throws Exception {
        // Given
        UUID orderId = UUID.randomUUID();
        when(orderService.getOrder(orderId)).thenThrow(new OrderNotFoundException(orderId));

        // When / Then — GlobalExceptionHandler returns 404 with ProblemDetail
        mockMvc.perform(get("/api/orders/{orderId}", orderId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Order Not Found"))
                .andExpect(jsonPath("$.detail").value("Order not found: " + orderId));
    }

    // --- GET /api/orders?customerId=... ---

    @Test
    void getOrdersByCustomer_shouldReturn200WithList() throws Exception {
        // Given
        UUID customerId = UUID.randomUUID();
        List<Order> orders = List.of(
                buildOrder(UUID.randomUUID(), customerId, OrderStatus.PLACED, new BigDecimal("100.00"), null),
                buildOrder(UUID.randomUUID(), customerId, OrderStatus.COMPLETED, new BigDecimal("200.00"), null)
        );

        when(orderService.getOrdersByCustomer(customerId)).thenReturn(orders);

        // When / Then
        mockMvc.perform(get("/api/orders")
                        .param("customerId", customerId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].status").value("PLACED"))
                .andExpect(jsonPath("$[1].status").value("COMPLETED"));
    }

    @Test
    void getOrdersByCustomer_shouldReturnEmptyList_whenNoOrders() throws Exception {
        // Given
        UUID customerId = UUID.randomUUID();
        when(orderService.getOrdersByCustomer(customerId)).thenReturn(List.of());

        // When / Then
        mockMvc.perform(get("/api/orders")
                        .param("customerId", customerId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    // --- Helper ---

    private Order buildOrder(UUID orderId, UUID customerId, OrderStatus status,
                             BigDecimal totalAmount, String failureReason) {
        Order order = Order.builder()
                .id(orderId)
                .customerId(customerId)
                .status(status)
                .totalAmount(totalAmount)
                .failureReason(failureReason)
                .createdAt(Instant.now())
                .build();
        return order;
    }
}
