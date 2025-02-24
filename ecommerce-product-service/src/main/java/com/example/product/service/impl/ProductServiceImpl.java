package com.example.product.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.example.common.domain.ResponseResult;
import com.example.common.exception.DatabaseException;
import com.example.common.exception.NotFoundException;
import com.example.common.exception.SystemException;
import com.example.common.util.UserContextUtil;
import com.example.product.convert.ProductInfoVoConvert;
import com.example.product.domain.dto.AddProductDto;
import com.example.product.domain.dto.DecProductDto;
import com.example.product.domain.dto.ListProductsDto;
import com.example.product.domain.dto.SearchProductsDto;
import com.example.product.domain.po.Category;
import com.example.product.domain.po.ProCateRel;
import com.example.product.domain.po.Product;
import com.example.product.domain.vo.ProductInfoVo;
import com.example.product.enums.ProductStatusEnum;
import com.example.product.mapper.productMapper;
import com.example.product.service.ICategoryService;
import com.example.product.service.IProCateRelService;
import com.example.product.service.IProductService;
import jakarta.annotation.Resource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.elasticsearch.action.get.GetRequest;
import org.elasticsearch.action.get.GetResponse;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.update.UpdateRequest;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.index.query.RangeQueryBuilder;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class ProductServiceImpl extends ServiceImpl<productMapper, Product> implements IProductService {

    @Resource
    private productMapper productMapper;

    @Resource
    private ICategoryService iCategoryService;

    @Resource
    private IProCateRelService iProCateRelService;

//    @Resource
//    private final ProductInfoVoConvert productInfoVoConvert;

    @Resource
    private RestHighLevelClient elasticsearchClient;


    // ================================ 查询服务 ================================

    /**
     * 根据商品ID查询商品信息
     *
     * @param productId
     */
    @Override
    public ResponseResult<ProductInfoVo> getProductInfoById(Long productId) {
//        // 根据商品id查询商品信息
//        Product product = productMapper.selectById(productId);
//        if (product == null) {
//            log.error("商品不存在，productId: {}", productId);
//            return ResponseResult.error(ProductStatusEnum.PRODUCT_NOT_EXIST.getErrorCode(), ProductStatusEnum.PRODUCT_NOT_EXIST.getErrorMessage());
//        }
//
//        // 根据 productId 从 ProCateRel 表查询商品分类信息
//        List<ProCateRel> proCateRels = iProCateRelService.list(Wrappers.<ProCateRel>lambdaQuery().eq(ProCateRel::getProductId, productId));
//        if (proCateRels.isEmpty()) {
//            log.error("商品分类不存在，productId: {}", productId);
//            return ResponseResult.error(ProductStatusEnum.PRODUCT_CATEGORY_NOT_EXIST.getErrorCode(), ProductStatusEnum.PRODUCT_CATEGORY_NOT_EXIST.getErrorMessage());
//        }
//
//        // 获取商品分类ID集合
//        List<Long> categoryIds = proCateRels.stream().map(ProCateRel::getCategoryId).collect(Collectors.toList());
//        // 根据分类ID集合查询分类信息
//        List<Category> categories = iCategoryService.listByIds(categoryIds);
//        // 查询商品分类信息
//        List<String> categoryNames = categories.stream().map(Category::getCategoryName).collect(Collectors.toList());
//
//        // 封装商品信息
//        ProductInfoVo productInfoVo = ProductInfoVo.builder()
//                .id(product.getId())
//                .name(product.getName())
//                .description(product.getDescription())
//                .price(product.getPrice())
//                .sold(product.getSold())
//                .stoke(product.getStoke())
//                .merchantName(product.getMerchantName())
//                .categories(categoryNames)
//                .status(product.getStatus())
//                .createTime(product.getCreateTime())
//                .updateTime(product.getUpdateTime())
//                .build();
//        return ResponseResult.success(productInfoVo);
        GetRequest request = new GetRequest("products", productId.toString());
        try {
            GetResponse response = elasticsearchClient.get(request, RequestOptions.DEFAULT);
            if (!response.isExists()) {
                throw new  NotFoundException("商品不存在");
            }
            Map<String, Object> source = response.getSourceAsMap();
            ProductInfoVo vo = convertToVo(source);
            return ResponseResult.success(vo);
        } catch (IOException e) {
            throw new RuntimeException("ES查询失败", e);
        }
    }

    /**
     * 指定某种类别查询商品信息
     *
     * @param listProductsDto
     */
    @Override
    public ResponseResult<IPage<ProductInfoVo>> getProductInfoByCategory(ListProductsDto listProductsDto) {
//        // 当前页面
//        int current = listProductsDto.getPage() == null ? 1 : listProductsDto.getPage();
//        if (current < 1) {
//            current = 1;
//        }
//        // 每页显示条数
//        int size = listProductsDto.getPageSize() == null ? 20 : listProductsDto.getPageSize();
//        if (size < 1) {
//            size = 20;
//        }
//        // 类型名称
//        String categoryName = listProductsDto.getCategoryName();
//
//        // 创建分页对象
//        Page<Product> page = new Page<>(current, size);
//
//        // 分类名称若为null则查询所有商品
//        if (categoryName == null || "".equals(categoryName)) {
//            // 查询所有商品并进行分页
//            IPage<Product> productPage = productMapper.selectPage(page, null);
//            if (productPage.getRecords().isEmpty()) {
//                log.error("商品不存在");
//                return ResponseResult.error(ProductStatusEnum.PRODUCT_NOT_EXIST.getErrorCode(), ProductStatusEnum.PRODUCT_NOT_EXIST.getErrorMessage());
//            }
//
//            // 封装商品信息
//            IPage<ProductInfoVo> productInfoVoPage = productPage.convert(productInfoVoConvert::convertToProductInfoVo);
//            return ResponseResult.success(productInfoVoPage);
//        } else {
//            // 不为null则根据分类名称查询分类信息
//            Category category = iCategoryService.getOne(Wrappers.<Category>lambdaQuery().eq(Category::getCategoryName, categoryName));
//            if (category == null) {
//                log.error("商品分类不存在，categoryName: {}", categoryName);
//                return ResponseResult.error(ProductStatusEnum.PRODUCT_CATEGORY_NOT_EXIST.getErrorCode(), ProductStatusEnum.PRODUCT_CATEGORY_NOT_EXIST.getErrorMessage());
//            }
//
//            // 根据分类ID查询商品ID
//            List<ProCateRel> proCateRels = iProCateRelService.list(Wrappers.<ProCateRel>lambdaQuery().eq(ProCateRel::getCategoryId, category.getId()));
//            if (proCateRels.isEmpty()) {
//                log.error("该分类下的商品不存在，categoryName: {}", categoryName);
//                return ResponseResult.error(ProductStatusEnum.CATEGORY_PRODUCT_NOT_EXIST.getErrorCode(), ProductStatusEnum.CATEGORY_PRODUCT_NOT_EXIST.getErrorMessage());
//            }
//
//            // 获取商品ID集合
//            List<Long> productIds = proCateRels.stream()
//                    .map(ProCateRel::getProductId)
//                    .collect(Collectors.toList());
//
//            // 根据商品ID集合查询商品信息并进行分页
//            IPage<Product> productPage = productMapper.selectPage(page, Wrappers.<Product>lambdaQuery().in(Product::getId, productIds));
//
//            // 封装商品信息
//            IPage<ProductInfoVo> productInfoVoPage = productPage.convert(productInfoVoConvert::convertToProductInfoVo);
//            return ResponseResult.success(productInfoVoPage);
//        }
        SearchRequest searchRequest = new SearchRequest("products");
        SearchSourceBuilder sourceBuilder = new SearchSourceBuilder();

        // 构建查询条件
        BoolQueryBuilder boolQuery = QueryBuilders.boolQuery();
        if (StringUtils.hasText(listProductsDto.getCategoryName())) {
            boolQuery.filter(QueryBuilders.termQuery("categories", listProductsDto.getCategoryName()));
        }

        // 分页设置
        int page = listProductsDto.getPage() != null ? listProductsDto.getPage() : 1;
        int size = listProductsDto.getPageSize() != null ? listProductsDto.getPageSize() : 20;
        sourceBuilder.query(boolQuery)
                .from((page - 1) * size)
                .size(size);

        searchRequest.source(sourceBuilder);

        try {
            SearchResponse response = elasticsearchClient.search(searchRequest, RequestOptions.DEFAULT);
            List<ProductInfoVo> products = Arrays.stream(response.getHits().getHits())
                    .map(hit -> convertToVo(hit.getSourceAsMap()))
                    .collect(Collectors.toList());

            // 构建分页结果
            Page<ProductInfoVo> pageResult = new Page<>(page, size, response.getHits().getTotalHits().value);
            pageResult.setRecords(products);
            return ResponseResult.success(pageResult);
        } catch (IOException e) {
            throw new RuntimeException("ES查询失败", e);
        }
    }

    /**
     * 指定条件查询商品信息
     *
     * @param searchProductsDto
     */
    @Override
    public ResponseResult<IPage<ProductInfoVo>> seachProductInfo(SearchProductsDto searchProductsDto) {
//        if (searchProductsDto == null) {
//            log.error("参数不能为空");
//            return ResponseResult.error(ProductStatusEnum.PARAM_NOT_NULL.getErrorCode(), ProductStatusEnum.PARAM_NOT_NULL.getErrorMessage());
//        }

//        // 当前页面
//        int current = searchProductsDto.getPage() == null ? 1 : searchProductsDto.getPage();
//        if (current < 1) {
//            current = 1;
//        }
//        // 每页显示条数
//        int size = searchProductsDto.getPageSize() == null ? 20 : searchProductsDto.getPageSize();
//        if (size < 1) {
//            size = 20;
//        }
//
//        // 创建分页对象
//        Page<Product> page = new Page<>(current, size);
//
//        // 构建查询条件
//        LambdaQueryWrapper<Product> queryWrapper = new LambdaQueryWrapper<>();
//
//        // 商品名称
//        if (searchProductsDto.getProductName() != null && !"".equals(searchProductsDto.getProductName())) {
//            queryWrapper.like(Product::getName, searchProductsDto.getProductName());
//        }
//
//        // 价格范围
//        if (searchProductsDto.getPriceLow() != null && searchProductsDto.getPriceHigh() != null) {
//            queryWrapper.between(Product::getPrice, searchProductsDto.getPriceLow(), searchProductsDto.getPriceHigh());
//        } else if (searchProductsDto.getPriceLow() != null) {
//            queryWrapper.ge(Product::getPrice, searchProductsDto.getPriceLow());
//        } else if (searchProductsDto.getPriceHigh() != null) {
//            queryWrapper.le(Product::getPrice, searchProductsDto.getPriceHigh());
//        }
//
//        // 销量
//        if (searchProductsDto.getStoke() != null) {
//            queryWrapper.ge(Product::getStoke, searchProductsDto.getStoke());
//        }
//
//        // 库存
//        if (searchProductsDto.getSold() != null) {
//            queryWrapper.ge(Product::getSold, searchProductsDto.getSold());
//        }
//
//        // 商家名称
//        if (searchProductsDto.getMerchantName() != null && !"".equals(searchProductsDto.getMerchantName())) {
//            queryWrapper.like(Product::getMerchantName, searchProductsDto.getMerchantName());
//        }
//
//        // 分类名称
//        if (searchProductsDto.getCategoryName() != null && !"".equals(searchProductsDto.getCategoryName())) {
//            // 根据分类名称查询分类ID
//            Category category = iCategoryService.getOne(
//                    Wrappers.<Category>lambdaQuery().eq(Category::getCategoryName, searchProductsDto.getCategoryName())
//            );
//            if (category == null) {
//                log.error("商品分类不存在，categoryName: {}", searchProductsDto.getCategoryName());
//                return ResponseResult.error(ProductStatusEnum.PRODUCT_CATEGORY_NOT_EXIST.getErrorCode(), ProductStatusEnum.PRODUCT_CATEGORY_NOT_EXIST.getErrorMessage());
//            }
//
//            // 根据分类ID查询商品ID
//            List<ProCateRel> proCateRels = iProCateRelService.list(
//                    Wrappers.<ProCateRel>lambdaQuery().eq(ProCateRel::getCategoryId, category.getId())
//            );
//            if (proCateRels.isEmpty()) {
//                log.error("该分类下的商品不存在，categoryName: {}", searchProductsDto.getCategoryName());
//                return ResponseResult.error(ProductStatusEnum.CATEGORY_PRODUCT_NOT_EXIST.getErrorCode(), ProductStatusEnum.CATEGORY_PRODUCT_NOT_EXIST.getErrorMessage());
//            }
//
//            // 获取商品ID集合
//            List<Long> productIds = proCateRels.stream()
//                    .map(ProCateRel::getProductId)
//                    .collect(Collectors.toList());
//
//            // 添加商品ID查询条件
//            queryWrapper.in(Product::getId, productIds);
//        }
//
//        // 执行分页查询
//        IPage<Product> productPage = productMapper.selectPage(page, queryWrapper);
//
//        // 封装商品信息
//        IPage<ProductInfoVo> productInfoVoPage = productPage.convert(productInfoVoConvert::convertToProductInfoVo);
//
//        return ResponseResult.success(productInfoVoPage);
        SearchRequest searchRequest = new SearchRequest("products");
        SearchSourceBuilder sourceBuilder = new SearchSourceBuilder();
        BoolQueryBuilder boolQuery = QueryBuilders.boolQuery();

        // 商品名称模糊查询
        if (StringUtils.hasText(searchProductsDto.getProductName())) {
            boolQuery.must(QueryBuilders.matchQuery("name", searchProductsDto.getProductName()));
        }

        // 价格范围查询
        if (searchProductsDto.getPriceLow() != null || searchProductsDto.getPriceHigh() != null) {
            RangeQueryBuilder rangeQuery = QueryBuilders.rangeQuery("price");
            if (searchProductsDto.getPriceLow() != null) rangeQuery.gte(searchProductsDto.getPriceLow());
            if (searchProductsDto.getPriceHigh() != null) rangeQuery.lte(searchProductsDto.getPriceHigh());
            boolQuery.filter(rangeQuery);
        }

        // 分类查询
        if (StringUtils.hasText(searchProductsDto.getCategoryName())) {
            boolQuery.filter(QueryBuilders.termQuery("categories", searchProductsDto.getCategoryName()));
        }

        // 分页设置
        int page = searchProductsDto.getPage() != null ? searchProductsDto.getPage() : 1;
        int size = searchProductsDto.getPageSize() != null ? searchProductsDto.getPageSize() : 20;
        sourceBuilder.query(boolQuery)
                .from((page - 1) * size)
                .size(size);

        searchRequest.source(sourceBuilder);

        try {
            SearchResponse response = elasticsearchClient.search(searchRequest, RequestOptions.DEFAULT);
            List<ProductInfoVo> products = Arrays.stream(response.getHits().getHits())
                    .map(hit -> convertToVo(hit.getSourceAsMap()))
                    .collect(Collectors.toList());

            Page<ProductInfoVo> pageResult = new Page<>(page, size, response.getHits().getTotalHits().value);
            pageResult.setRecords(products);
            return ResponseResult.success(pageResult);
        } catch (IOException e) {
            throw new RuntimeException("ES查询失败", e);
        }
    }

    // ================================ 库存操作 ================================
    /**
     * 增加库存
     *
     * @param addProductDto
     */
    @Override
    public ResponseResult<Object> addProductStock(AddProductDto addProductDto) {
        Long userId = UserContextUtil.getUserId();
        if (userId == null) {
            log.error("用户未登录");
            return ResponseResult.error(ProductStatusEnum.USER_NOT_LOGIN.getErrorCode(), ProductStatusEnum.USER_NOT_LOGIN.getErrorMessage());
        }

        // 根据商品ID查询商品信息
        Long productId = addProductDto.getProductId();
        Product product = productMapper.selectById(productId);

        if (product == null) {
            log.error("商品不存在，productId: {}", productId);
            return ResponseResult.error(ProductStatusEnum.PRODUCT_NOT_EXIST.getErrorCode(), ProductStatusEnum.PRODUCT_NOT_EXIST.getErrorMessage());
        }

        // 减少销量
        Integer decSold = addProductDto.getAddStock();
        if (product.getSold() < decSold) {
            log.error("销量不足，productId: {}", productId);
            return ResponseResult.error(ProductStatusEnum.PRODUCT_SOLD_NOT_ENOUGH.getErrorCode(), ProductStatusEnum.PRODUCT_SOLD_NOT_ENOUGH.getErrorMessage());
        }
        product.setSold(product.getSold() - decSold);

        // 增加库存
        Integer addStock = addProductDto.getAddStock();
        product.setStoke(product.getStoke() + addStock);

        // 同步到ES
        syncProductToES(product);
        return ResponseResult.success();

    }

    /**
     * 减少库存
     *
     * @param decProductDto
     */
    @Override
    public ResponseResult<Object> decProductStock(DecProductDto decProductDto) {
        Long userId = UserContextUtil.getUserId();
        if (userId == null) {
            log.error("用户未登录");
            return ResponseResult.error(ProductStatusEnum.USER_NOT_LOGIN.getErrorCode(), ProductStatusEnum.USER_NOT_LOGIN.getErrorMessage());
        }

        // 根据商品ID查询商品信息
        Long productId = decProductDto.getProductId();
        Product product = productMapper.selectById(productId);

        if (product == null) {
            log.error("商品不存在，productId: {}", productId);
            return ResponseResult.error(ProductStatusEnum.PRODUCT_NOT_EXIST.getErrorCode(), ProductStatusEnum.PRODUCT_NOT_EXIST.getErrorMessage());
        }

        // 减少库存
        Integer decStock = decProductDto.getDecStock();
        if (product.getStoke() < decStock) {
            log.error("库存不足，productId: {}", productId);
            return ResponseResult.error(ProductStatusEnum.PRODUCT_STOCK_NOT_ENOUGH.getErrorCode(), ProductStatusEnum.PRODUCT_STOCK_NOT_ENOUGH.getErrorMessage());
        }

        product.setStoke(product.getStoke() - decStock);
        product.setSold(product.getSold() + decStock);

        // 同步到ES
        syncProductToES(product);
        return ResponseResult.success();
    }


    // ================================ 数据同步 ================================
    /**
     * 更新商品信息（双写MySQL和ES）
     */
    @Transactional
    public void syncProductToES(Product product) {
        // 1. 更新MySQL
        int update = productMapper.updateById(product);
        if (update == 0) {
            log.error("更新库存失败，productId: {}", product.getId());
            throw new DatabaseException("更新库存失败");
        }

        // 2. 同步到Elasticsearch
        try {
            UpdateRequest request = new UpdateRequest("products", product.getId().toString())
                    .doc(convertToMap(product), XContentType.JSON)
                    .docAsUpsert(true); // 不存在时创建文档

            elasticsearchClient.update(request, RequestOptions.DEFAULT);
            log.info("商品同步到ES成功，ID: {}", product.getId());
        } catch (IOException e) {
            log.error("商品同步到ES失败，ID: {}", product.getId(), e);
            throw new SystemException("ES同步失败", e);
        }
    }

    private Map<String, Object> convertToMap(Product product) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", product.getId());
        map.put("name", product.getName());
        map.put("description", product.getDescription());
        map.put("price", product.getPrice());
        map.put("sold", product.getSold());
        map.put("stock", product.getStoke());
        map.put("merchantName", product.getMerchantName());
        map.put("status", product.getStatus());
        map.put("createTime", product.getCreateTime());
        map.put("updateTime", product.getUpdateTime());

        // 获取关联表信息
        List<ProCateRel> proCateRels = iProCateRelService.list(Wrappers.<ProCateRel>lambdaQuery().eq(ProCateRel::getProductId, product.getId()));
        // 获取分类id列表
        List<Long> categoryIds = proCateRels.stream().map(ProCateRel::getCategoryId).collect(Collectors.toList());
        // 获取分类名称列表
        List<Category> categories = iCategoryService.listByIds(categoryIds);
        List<String> categoryNames = categories.stream().map(Category::getCategoryName).collect(Collectors.toList());
        map.put("categories", categoryNames);
        return map;
    }

    private ProductInfoVo convertToVo(Map<String, Object> source) {
        return ProductInfoVo.builder()
                .id(Long.parseLong(source.get("id").toString()))
                .name((String) source.get("name"))
                .description((String) source.get("description"))
                .price((Float) source.get("price"))
                .sold((Integer) source.get("sold"))
                .stoke((Integer) source.get("stock"))
                .merchantName((String) source.get("merchantName"))
                .categories((List<String>) source.get("categories"))
                .status((Integer) source.get("status"))
                .createTime((LocalDateTime) source.get("createTime"))
                .updateTime((LocalDateTime) source.get("updateTime"))
                .build();
    }


}
