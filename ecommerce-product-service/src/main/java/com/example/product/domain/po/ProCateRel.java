package com.example.product.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("product_category_relation")
@Schema(description = "商品分类关联实体类")
public class ProCateRel implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        @TableId(value = "id", type = IdType.ASSIGN_ID)
        @Schema(description = "商品分类关联ID")
        private Long id;

        @Schema(description = "商品ID")
        private Long productId;

        @Schema(description = "分类ID")
        private Long categoryId;

        @Schema(description = "创建时间")
        private LocalDateTime createTime;

        @Schema(description = "更新时间")
        private LocalDateTime updateTime;
}
