package com.example.cart.mapper;

import com.example.cart.domain.po.CartItem;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * <p>
 * 购物车物品数据库 Mapper 接口
 * </p>
 *
 * @author author
 * @since 2025-02-14
 */
@Mapper
public interface CartItemMapper extends BaseMapper<CartItem> {

}
