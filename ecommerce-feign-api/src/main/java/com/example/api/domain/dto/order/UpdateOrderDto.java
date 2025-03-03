package com.example.api.domain.dto.order;

import com.example.api.domain.po.CartItem;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

@Data
@Schema(description = "更新订单数据")
public class UpdateOrderDto {
    @Schema(description = "订单ID")
    @NotEmpty
    private String orderId;
    @Schema(description = "使用的货币")
    private String userCurrency;
    @Schema(description = "地址")
    private Long addressId;
    @Schema(description = "电子邮件")
    @Email
    private String email;
    @Schema(description = "下单的商品")
    @NotEmpty
    private List<CartItem> cartItems;
}
