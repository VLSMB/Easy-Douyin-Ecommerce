package com.example.payment.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.example.api.client.OrderClient;
import com.example.api.domain.dto.order.MarkOrderPaidDto;
import com.example.api.domain.vo.order.OrderInfoVo;
import com.example.common.domain.ResponseResult;
import com.example.common.exception.SystemException;
import com.example.common.util.UserContextUtil;
import com.example.payment.domain.dto.ChargeCancelDto;
import com.example.payment.domain.dto.ChargeDto;
import com.example.payment.domain.dto.CreditDto;
import com.example.payment.domain.dto.CreditUpdateDto;
import com.example.payment.domain.po.Credit;
import com.example.payment.domain.po.Transaction;
import com.example.payment.domain.vo.ChargeVo;
import com.example.payment.domain.vo.CreditVo;
import com.example.payment.enums.PaymentStatusEnum;
import com.example.payment.mapper.CreditMapper;
import com.example.payment.service.CreditService;
import com.example.payment.service.TransactionService;
import io.seata.spring.annotation.GlobalTransactional;
import jakarta.annotation.Resource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.javassist.tools.rmi.RemoteException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ConcurrentModificationException;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class CreditServiceImpl extends ServiceImpl<CreditMapper, Credit> implements CreditService {

    @Resource
    private CreditMapper creditMapper;

    @Resource
    private TransactionService transactionService;

    @Resource
    private OrderClient orderClient;

    /**
     * 支付功能
     *
     * @param chargeDto 订单支付信息
     * @rerurn chargeVo 支付结果
     */
    @GlobalTransactional(name = "paymentCharge", rollbackFor = Exception.class)
    @Transactional
    @Override
    public ResponseResult<ChargeVo> charge(ChargeDto chargeDto) {

        String transId = UUID.randomUUID().toString();
        // 判断用户是否登录
        Long userId = UserContextUtil.getUserId();
        if (userId == null) {
            log.error("用户未登录");
            return ResponseResult.error(PaymentStatusEnum.USER_NOT_LOGIN.getErrorCode(), PaymentStatusEnum.USER_NOT_LOGIN.getErrorMessage());
        }

        // 获取订单id
        String orderId = chargeDto.getOrderId();
        // 获取付款金额
        Float amount = chargeDto.getAmount();

        // 调用远程接口查询订单是否存在
        ResponseResult<OrderInfoVo> orderInfo = orderClient.getOrderById(orderId);
        if (orderInfo == null || orderInfo.getCode() != 200 || orderInfo.getData() == null) {
            log.error("订单不存在或查询失败");
            return ResponseResult.error(PaymentStatusEnum.ORDER_NOT_EXIST.getErrorCode(), PaymentStatusEnum.ORDER_NOT_EXIST.getErrorMessage());
        }

        // 获取银行卡信息
        String creditId = chargeDto.getCreditId();
        Credit credit = creditMapper.selectById(creditId);

        // 判断银行卡是否存在
        if (credit == null) {
            log.error("银行卡不存在");
            return ResponseResult.error(PaymentStatusEnum.CREDIT_NOT_EXISTED.getErrorCode(), PaymentStatusEnum.CREDIT_NOT_EXISTED.getErrorMessage());
        }

        // 查询银行卡状态
        Integer status = credit.getStatus();
        if (status != 0) {
            log.error("银行卡状态异常");
            saveFailedTransaction(transId, userId, orderId, creditId, amount, "银行卡状态异常");
            return ResponseResult.error(PaymentStatusEnum.CREDIT_STATUS_ERROR.getErrorCode(), PaymentStatusEnum.CREDIT_STATUS_ERROR.getErrorMessage());
        }

        // 获取银行卡余额
        Float balance = credit.getBalance();

        // 判断银行卡余额是否足够
        if (balance < amount) {
            log.error("银行卡余额不足");
            saveFailedTransaction(transId, userId, orderId, creditId, amount, "银行卡余额不足");
//            ChargeVo chargeVo = ChargeVo.builder()
//                    .transactionId(transId)
//                    .build();
//            return ResponseResult.success(chargeVo);
            return ResponseResult.error(PaymentStatusEnum.PAYMENT_FAILED.getErrorCode(), PaymentStatusEnum.PAYMENT_FAILED.getErrorMessage());
        }

        // 扣除银行卡余额
        credit.setBalance(balance - amount);
        int updateCount = creditMapper.updateById(credit);
        if (updateCount == 0) {
            log.error("并发冲突导致支付失败");
            saveFailedTransaction(transId, userId, orderId, creditId, amount, "并发冲突导致支付失败");
            return ResponseResult.error(PaymentStatusEnum.PAYMENT_CONFLICT.getErrorCode(), PaymentStatusEnum.PAYMENT_CONFLICT.getErrorMessage());
        }

        // 保存交易记录(成功)
        transactionService.save(Transaction.builder()
                .transId(transId)
                .userId(userId)
                .orderId(orderId)
                .creditId(creditId)
                .amount(amount)
                .status(0)
                .build());

        // 标记订单已支付
        MarkOrderPaidDto markOrderPaidDto = new MarkOrderPaidDto();
        markOrderPaidDto.setOrderId(orderId);
        ResponseResult<Object> markResult =  orderClient.markOrderPaid(markOrderPaidDto);
        if (markResult == null || markResult.getCode() != 200) {
            log.error("标记订单失败，orderId: {} ", orderId);
            throw new SystemException("标记订单失败，orderId: " + orderId, new RemoteException("订单服务调用失败"));
        }

        ChargeVo chargeVo = ChargeVo.builder()
                .transactionId(transId)
                .build();

        // 返回支付结果
        return ResponseResult.success(chargeVo);
    }



    /**
     * 取消支付
     *
     * @param transactionId
     * @return
     */
    @GlobalTransactional(name = "paymentCancel", rollbackFor = Exception.class)
    @Transactional
    @Override
    public ResponseResult<Object> cancelCharge(String transactionId) {
        try {
            // 1. 用户登录验证
            Long userId = UserContextUtil.getUserId();
            if (userId == null) {
                log.error("用户未登录");
                return ResponseResult.error(PaymentStatusEnum.USER_NOT_LOGIN.getErrorCode(), PaymentStatusEnum.USER_NOT_LOGIN.getErrorMessage());
            }

            // 2. 查询交易记录
            Transaction transaction = transactionService.getById(transactionId);
            if (transaction == null || transaction.getDeleted() != 0) {
                log.error("交易记录不存在：{}", transactionId);
                return ResponseResult.error(PaymentStatusEnum.PAYMENT_NOT_FOUND.getErrorCode(), PaymentStatusEnum.PAYMENT_NOT_FOUND.getErrorMessage());
            }

            // 3. 验证交易归属
            if (!userId.equals(transaction.getUserId())) {
                log.error("无权操作该交易：{}", transactionId);
                return ResponseResult.error(PaymentStatusEnum.PAYMENT_ACCESS_DENIED.getErrorCode(), PaymentStatusEnum.PAYMENT_ACCESS_DENIED.getErrorMessage());
            }

            // 4. 检查交易状态
            if (transaction.getStatus() != 0) {
                log.error("交易状态不可取消：{}", transaction.getStatus());
                return ResponseResult.error(PaymentStatusEnum.PAYMENT_CANNOT_CANCEL.getErrorCode(), PaymentStatusEnum.PAYMENT_CANNOT_CANCEL.getErrorMessage());
            }

            // 5. 查询银行卡信息
            Credit credit = creditMapper.selectById(transaction.getCreditId());
            if (credit == null || credit.getStatus() != 0) {
                log.error("银行卡不可用：{}", transaction.getCreditId());
                return ResponseResult.error(PaymentStatusEnum.CREDIT_STATUS_ERROR.getErrorCode(), PaymentStatusEnum.CREDIT_STATUS_ERROR.getErrorMessage());
            }

            // 6. 退还金额（带乐观锁校验）
            Float newBalance = credit.getBalance() + transaction.getAmount();
            credit.setBalance(newBalance);
            int updateCount = creditMapper.updateById(credit);

            if (updateCount == 0) {
                log.error("银行卡余额更新冲突");
                throw new SystemException("银行卡余额更新冲突", new ConcurrentModificationException());
            }

            // 7. 更新交易状态
            transaction.setStatus(1);
            transaction.setReason("用户取消支付");
            transactionService.updateById(transaction);

            // 8. 标记订单未支付
//            MarkOrderUnpaidDto dto = new MarkOrderUnpaidDto();
//            dto.setOrderId(transaction.getOrderId());
//            ResponseResult<?> result = orderClient.markOrderUnpaid(dto);
//
//            if (result.getCode() != 200) {
//                log.error("订单状态更新失败：{}", transaction.getOrderId());
//                throw new RemoteException("订单服务调用失败");
//            }

            return ResponseResult.success();
        } catch (Exception e) {
            log.error("取消支付失败：", e);
            throw e; // 触发全局事务回滚
        }
    }

    /**
     * 定期取消支付
     *
     * @param chargeCancelDto
     * @return
     */
    @Override
    public ResponseResult<Object> autoCancelCharge(ChargeCancelDto chargeCancelDto) {
        return null;
    }

    /**
     * 输入信用卡信息
     *
     * @param creditDto
     */
    @Override
    public ResponseResult<CreditVo> createCredit(CreditDto creditDto) {
        // 判断用户是否登录
        Long userId = UserContextUtil.getUserId();
        if (userId == null) {
            log.error("用户未登录");
            return ResponseResult.error(PaymentStatusEnum.USER_NOT_LOGIN.getErrorCode(), PaymentStatusEnum.USER_NOT_LOGIN.getErrorMessage());
        }

        // 判断信用卡信息是否已存在
        Credit creditExist = creditMapper.selectById(creditDto.getCardNumber());
        if (creditExist != null) {
            log.error("信用卡信息已录入");
            return ResponseResult.error(PaymentStatusEnum.CREDIT_BALANCE_NOT_ENOUGH.getErrorCode(), PaymentStatusEnum.CREDIT_BALANCE_NOT_ENOUGH.getErrorMessage());
        }

        LocalDate expireDate = creditDto.getExpireDate();
        if (expireDate.isBefore(LocalDate.now())) {
            log.error("信用卡已过期");
            return ResponseResult.error(PaymentStatusEnum.CREDIT_EXPIRED.getErrorCode(), PaymentStatusEnum.CREDIT_EXPIRED.getErrorMessage());
        }

        Credit credit = Credit.builder()
                .cardNumber(creditDto.getCardNumber())
                .cvv(creditDto.getCvv())
                .userId(userId)
                .balance(creditDto.getBalance())
                .expireDate(creditDto.getExpireDate())
                .status(0)
                .version(0)
                .deleted(0)
                .createTime(LocalDateTime.now())
                .updateTime(LocalDateTime.now())
                .build();
        int insert = creditMapper.insert(credit);
        if (insert == 0){
            log.error("信用卡信息保存失败");
            return ResponseResult.error(PaymentStatusEnum.CREDIT_SAVE_FAILED.getErrorCode(), PaymentStatusEnum.CREDIT_SAVE_FAILED.getErrorMessage());
        }

        CreditVo creditVo = CreditVo.builder()
                .cardNumber(credit.getCardNumber())
                .cvv(credit.getCvv())
                .userId(credit.getUserId())
                .balance(credit.getBalance())
                .expireDate(credit.getExpireDate())
                .status(credit.getStatus())
                .createTime(credit.getCreateTime())
                .updateTime(credit.getUpdateTime())
                .build();

        return ResponseResult.success(creditVo);
    }

    /**
     * 删除信用卡信息
     *
     * @param cardNumber
     */
    @Override
    public ResponseResult<Object> deleteCredit(String cardNumber) {
        Long userId = UserContextUtil.getUserId();
        if (userId == null) {
            log.error("用户未登录");
            return ResponseResult.error(PaymentStatusEnum.USER_NOT_LOGIN.getErrorCode(), PaymentStatusEnum.USER_NOT_LOGIN.getErrorMessage());
        }

        Credit credit = creditMapper.selectById(cardNumber);
        if (credit == null || credit.getDeleted() != 0) {
            log.error("信用卡信息不存在");
            return ResponseResult.error(PaymentStatusEnum.CREDIT_NOT_FOUND.getErrorCode(), PaymentStatusEnum.CREDIT_NOT_FOUND.getErrorMessage());
        }

        if (!userId.equals(credit.getUserId())) {
            log.error("无权删除该信用卡信息");
            return ResponseResult.error(PaymentStatusEnum.PAYMENT_ACCESS_DENIED.getErrorCode(), PaymentStatusEnum.PAYMENT_ACCESS_DENIED.getErrorMessage());
        }

        int delete = creditMapper.deleteById(cardNumber);
        if (delete == 0) {
            log.error("信用卡信息删除失败");
            return ResponseResult.error(PaymentStatusEnum.CREDIT_DELETE_FAILED.getErrorCode(), PaymentStatusEnum.CREDIT_DELETE_FAILED.getErrorMessage());
        }

        return ResponseResult.success("删除成功!");
    }

    /**
     * 更新信用卡信息
     *
     * @param credit
     */
    @Override
    public ResponseResult<CreditVo> updateCredit(CreditUpdateDto creditUpdateDto) {
        Long userId = UserContextUtil.getUserId();
        if (userId == null) {
            log.error("用户未登录");
            return ResponseResult.error(PaymentStatusEnum.USER_NOT_LOGIN.getErrorCode(), PaymentStatusEnum.USER_NOT_LOGIN.getErrorMessage());
        }
        CreditVo creditVo = new CreditVo();
        Credit credit = creditMapper.selectById(creditUpdateDto.getCardNumber());
        if (credit == null || credit.getDeleted() != 0) {
            log.error("信用卡信息不存在");
            return ResponseResult.error(PaymentStatusEnum.CREDIT_NOT_FOUND.getErrorCode(), PaymentStatusEnum.CREDIT_NOT_FOUND.getErrorMessage());
        }

        if (!userId.equals(credit.getUserId())) {
            log.error("无权更新该信用卡信息");
            return ResponseResult.error(PaymentStatusEnum.PAYMENT_ACCESS_DENIED.getErrorCode(), PaymentStatusEnum.PAYMENT_ACCESS_DENIED.getErrorMessage());
        }

        Float balance = creditUpdateDto.getBalance();
        if (balance != null && balance < 0) {
            log.error("余额不能小于0");
            return ResponseResult.error(PaymentStatusEnum.CREDIT_UPDATE_FAILED.getErrorCode(), PaymentStatusEnum.CREDIT_UPDATE_FAILED.getErrorMessage());
        } else if (balance != null) {
            credit.setBalance(balance);
            creditVo.setBalance(balance);
        }

        Integer status = creditUpdateDto.getStatus();
        if (status != null) {
            credit.setStatus(status);
            creditVo.setStatus(status);
        }

        LocalDate expireDate = creditUpdateDto.getExpireDate();
        if (expireDate != null) {
            credit.setExpireDate(expireDate);
            creditVo.setExpireDate(expireDate);
        }

        credit.setUpdateTime(LocalDateTime.now());
        int update = creditMapper.updateById(credit);
        if (update == 0) {
            log.error("信用卡信息更新失败");
            return ResponseResult.error(PaymentStatusEnum.CREDIT_UPDATE_FAILED.getErrorCode(), PaymentStatusEnum.CREDIT_UPDATE_FAILED.getErrorMessage());
        }
        creditVo.setCardNumber(credit.getCardNumber());
        creditVo.setCvv(credit.getCvv());
        creditVo.setUserId(credit.getUserId());
        creditVo.setCreateTime(credit.getCreateTime());
        creditVo.setUpdateTime(credit.getUpdateTime());
        return ResponseResult.success(creditVo);
    }

    /**
     * 查询信用卡信息
     *
     * @param cardNumber
     */
    @Override
    public ResponseResult<CreditVo> getCredit(String cardNumber) {
        Long userId = UserContextUtil.getUserId();
        if (userId == null) {
            log.error("用户未登录");
            return ResponseResult.error(PaymentStatusEnum.USER_NOT_LOGIN.getErrorCode(), PaymentStatusEnum.USER_NOT_LOGIN.getErrorMessage());
        }

        Credit credit = creditMapper.selectById(cardNumber);
        if (credit == null || credit.getDeleted() != 0) {
            log.error("信用卡信息不存在");
            return ResponseResult.error(PaymentStatusEnum.CREDIT_NOT_FOUND.getErrorCode(), PaymentStatusEnum.CREDIT_NOT_FOUND.getErrorMessage());
        }

        if (!userId.equals(credit.getUserId())) {
            log.error("无权访问该信用卡信息");
            return ResponseResult.error(PaymentStatusEnum.PAYMENT_ACCESS_DENIED.getErrorCode(), PaymentStatusEnum.PAYMENT_ACCESS_DENIED.getErrorMessage());
        }

        CreditVo creditVo = CreditVo.builder()
                .cardNumber(credit.getCardNumber())
                .cvv(credit.getCvv())
                .userId(credit.getUserId())
                .balance(credit.getBalance())
                .expireDate(credit.getExpireDate())
                .status(credit.getStatus())
                .createTime(credit.getCreateTime())
                .updateTime(credit.getUpdateTime())
                .build();
        return ResponseResult.success(creditVo);
    }

    public void saveFailedTransaction(String transId, Long userId, String orderId, String creditId, Float amount, String reason) {
        transactionService.save(Transaction.builder()
                .transId(transId)
                .userId(userId)
                .orderId(orderId)
                .creditId(creditId)
                .amount(amount)
                .status(1)
                .reason(reason)
                .build());
    }

}
