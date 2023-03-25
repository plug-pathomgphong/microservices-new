package com.pathomphong.orderservice.service;

import com.pathomphong.orderservice.dto.InventoryResponse;
import com.pathomphong.orderservice.dto.OrdeLineItemsDto;
import com.pathomphong.orderservice.dto.OrderRequest;
import com.pathomphong.orderservice.model.Order;
import com.pathomphong.orderservice.model.OrderLineItems;
import com.pathomphong.orderservice.repository.OrderRepository;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;


import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional
public class OrderService {

    private final OrderRepository orderRepository;
    private final WebClient.Builder webClientBuilder;
    private final Tracer tracer;
    
    public String placeOrder(OrderRequest orderRequest){
        Order order = new Order();
        order.setOrderNumber(UUID.randomUUID().toString());

        List<OrderLineItems> orderLineItems = orderRequest.getOrderLineItemsDtoList()
                .stream()
                .map(this::mapToDto)
                .collect(Collectors.toList());

        order.setOrderLineItemsList(orderLineItems);

        List<String> skuCodes = order.getOrderLineItemsList().stream()
                .map(OrderLineItems::getSkuCode)
                .collect(Collectors.toList());

        Span inventoryServiceLookup = tracer.nextSpan().name("InventoryServiceLookup");

        try(Tracer.SpanInScope spanInScope = tracer.withSpan(inventoryServiceLookup.start())) {
            /*
            Call Inventory Service, and place order if product is in stock
             */
                InventoryResponse[] inventoryResponseArray = webClientBuilder.build().get()
                        .uri("http://inventory-service/api/inventory",
                                uriBuilder -> uriBuilder.queryParam("skuCode", skuCodes).build())
                        .retrieve()
                        .bodyToMono(InventoryResponse[].class)
                        .block();
                boolean allProductsInStock = Arrays.stream(inventoryResponseArray)
                        .allMatch(InventoryResponse::isInStock);

                if (allProductsInStock){
                    orderRepository.save(order);
                    return "Order Placed Successfully";
                }else {
                    throw new IllegalArgumentException("Product is not in stock, please try again later");
                }
        } finally {
            inventoryServiceLookup.end();
        }



    }

    private OrderLineItems mapToDto(OrdeLineItemsDto orderLineItemsDto) {
        OrderLineItems orderLineItems = new OrderLineItems();
        orderLineItems.setPrice(orderLineItemsDto.getPrice());
        orderLineItems.setQuantity(orderLineItemsDto.getQuantity());
        orderLineItems.setSkuCode(orderLineItemsDto.getSkuCode());
        return orderLineItems;
    }

}
