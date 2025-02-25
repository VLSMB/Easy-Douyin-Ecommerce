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
import com.example.common.exception.UserException;
import com.example.common.util.UserContextUtil;
import com.example.product.convert.ProductInfoVoConvert;
import com.example.product.domain.dto.*;
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
import org.springframework.beans.BeanUtils;
import org.springframework.beans.BeanWrapper;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.beans.FeatureDescriptor;
import java.io.IOException;
import java.text.SimpleDateFormat;
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

    @Resource
    private RestHighLevelClient elasticsearchClient;

    // ================================ 商品操作 ================================
    /**
     * 创建商品
     *
     * @param createProductDto
     */
    @Transactional
    @Override
    public ResponseResult<Object> createProduct(CreateProductDto createProductDto) {
        CheckUserLogin();

        // 1. 校验分类是否存在并获取分类ID
        List<Long> categoryIds = validateAndGetCategoryIds(createProductDto.getCategories());

        // 2. 构建商品实体
        Product product = Product.builder()
                .name(createProductDto.getName())
                .description(createProductDto.getDescription())
                .price(createProductDto.getPrice())
                .sold(0)
                .stoke(createProductDto.getStock() != null ? createProductDto.getStock() : 0) // 需要根据业务需求调整
                .merchantName(createProductDto.getMerchantName())
                .status(createProductDto.getStatus())
                .build();

        // 3. 保存商品
        if (!save(product)) {
            log.error("商品创建失败: {}", createProductDto);
            throw new DatabaseException("商品创建失败");
        }

        // 4. 保存分类关系
        saveProductCategories(product.getId(), categoryIds);

        // 5. 同步到Elasticsearch
        syncFullProductToES(product.getId());

        return ResponseResult.success();
    }

    /**
     * 更新商品信息
     *
     * @param updateProductDto
     */
    @Override
    public ResponseResult<Object> updateProduct(UpdateProductDto updateProductDto) {
        CheckUserLogin();

        // 1. 查询现有商品
        Product product = getById(updateProductDto.getId());
        if (product == null) {
            throw new NotFoundException("商品不存在");
        }

        // 2. 更新商品字段（使用BeanUtils处理非空字段）
        BeanUtils.copyProperties(updateProductDto, product, getNullPropertyNames(updateProductDto));

        // 3. 更新分类关系（如果传了categories）
        if (updateProductDto.getCategories() != null) {
            List<Long> categoryIds = validateAndGetCategoryIds(updateProductDto.getCategories());
            // 删除旧关系
            iProCateRelService.remove(Wrappers.<ProCateRel>lambdaQuery()
                    .eq(ProCateRel::getProductId, product.getId()));
            // 添加新关系
            saveProductCategories(product.getId(), categoryIds);
        }

        // 4. 更新商品
        if (!updateById(product)) {
            log.error("商品更新失败: {}", updateProductDto);
            throw new DatabaseException("商品更新失败");
        }

        // 5. 同步到Elasticsearch
        syncFullProductToES(product.getId());

        return ResponseResult.success();
    }

    // ================================ 查询服务 ================================

    /**
     * 根据商品ID查询商品信息
     *
     * @param productId
     */
    @Override
    public ResponseResult<ProductInfoVo> getProductInfoById(Long productId) {
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
        CheckUserLogin();

        // 根据商品ID查询商品信息
        Long productId = addProductDto.getProductId();
        Product product = productMapper.selectById(productId);

        if (product == null) {
            log.error("商品不存在，productId: {}", productId);
            return ResponseResult.error(ProductStatusEnum.PRODUCT_NOT_EXIST.getErrorCode(), ProductStatusEnum.PRODUCT_NOT_EXIST.getErrorMessage());
        }

        // 减少销量
        Integer decSold = addProductDto.getAddStock();
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
        CheckUserLogin();

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

    private Long CheckUserLogin() {
        Long userId = UserContextUtil.getUserId();
        if (userId == null) {
            log.error("用户未登录");
            throw new SystemException("用户未登录");
        }
        return userId;
    }

    private List<Long> validateAndGetCategoryIds(List<String> categoryNames) {
        List<Category> categories = iCategoryService.list(
                Wrappers.<Category>lambdaQuery().in(Category::getCategoryName, categoryNames)
        );

        if (categories.size() != categoryNames.size()) {
            List<String> existNames = categories.stream()
                    .map(Category::getCategoryName)
                    .collect(Collectors.toList());
            List<String> notExist = categoryNames.stream()
                    .filter(name -> !existNames.contains(name))
                    .collect(Collectors.toList());

            // 新增分类
            List<Category> newCategories = notExist.stream()
                    .map(name -> new Category().setCategoryName(name))
                    .collect(Collectors.toList());
            if (!iCategoryService.saveBatch(newCategories)) {
                throw new DatabaseException("保存分类失败");
            }

            // 重新查询分类
            categories = iCategoryService.list(
                    Wrappers.<Category>lambdaQuery().in(Category::getCategoryName, categoryNames)
            );
        }

        return categories.stream()
                .map(Category::getId)
                .collect(Collectors.toList());
    }

    private void saveProductCategories(Long productId, List<Long> categoryIds) {
        List<ProCateRel> relations = categoryIds.stream()
                .map(cid -> new ProCateRel(productId, cid))
                .collect(Collectors.toList());

        if (!iProCateRelService.saveBatch(relations)) {
            throw new RuntimeException("保存分类关系失败");
        }
    }

    /**
     * 完整同步商品数据到ES（包含分类名称）
     */
    private void syncFullProductToES(Long productId) {
        Product product = getById(productId);
        List<ProCateRel> relations = iProCateRelService.list(
                Wrappers.<ProCateRel>lambdaQuery().eq(ProCateRel::getProductId, productId)
        );

        List<String> categoryNames = iCategoryService.listByIds(
                relations.stream().map(ProCateRel::getCategoryId).collect(Collectors.toList())
        ).stream().map(Category::getCategoryName).collect(Collectors.toList());

        // 构建ES文档
        Map<String, Object> doc = new HashMap<>();
        doc.put("id", product.getId());
        doc.put("name", product.getName());
        doc.put("description", product.getDescription());
        doc.put("price", product.getPrice());
        doc.put("sold", product.getSold());
        doc.put("stoke", product.getStoke());
        doc.put("merchantName", product.getMerchantName());
        doc.put("status", product.getStatus());
        doc.put("categories", categoryNames);
        doc.put("createTime", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(product.getCreateTime()));
        doc.put("updateTime", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(product.getUpdateTime()));

        // 更新ES
        UpdateRequest request = new UpdateRequest("products", productId.toString())
                .doc(doc, XContentType.JSON)
                .docAsUpsert(true);

        try {
            elasticsearchClient.update(request, RequestOptions.DEFAULT);
        } catch (IOException e) {
            log.error("ES同步失败，productId: {}", productId, e);
            // 此处可以添加重试逻辑或消息队列处理
        }
    }

    private String[] getNullPropertyNames(Object source) {
        BeanWrapper src = new BeanWrapperImpl(source);
        return Arrays.stream(src.getPropertyDescriptors())
                .map(FeatureDescriptor::getName)
                .filter(name -> src.getPropertyValue(name) == null)
                .toArray(String[]::new);
    }
}


