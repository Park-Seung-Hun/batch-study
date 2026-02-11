package com.test.batchstudy.listener;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.retry.RetryException;
import org.springframework.core.retry.RetryListener;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryState;
import org.springframework.core.retry.Retryable;
import org.springframework.stereotype.Component;

/**
 * Week 05: 재시도 발생 시 로깅하는 Listener
 * <p>
 * Retry는 일시적 오류(DB 교착 상태 등)에 대한 자동 복구 메커니즘이다.
 * 성공하면 기록이 불필요하므로 DB 저장 없이 로깅만 수행한다.
 * <p>
 * ErrorIsolationSkipListener와 동일하게 @StepScope 사용하지 않음.
 */
@Slf4j
@Component
public class RetryLoggingListener implements RetryListener {

    /**
     * 매 실행마다 호출 — 실제 재시도(retryCount > 0)인 경우만 WARN 로깅
     *
     * 주의: 첫 시도(retryCount=0)에서도 호출되므로,
     * state.getLastException()은 retryCount > 0일 때만 안전하게 접근 가능
     */
    @Override
    public void onRetryableExecution(RetryPolicy policy, Retryable<?> retryable, RetryState state) {
        if (state.getRetryCount() > 0) {
            log.warn("Retry attempt #{} — exception: {}",
                    state.getRetryCount(),
                    state.getLastException().getMessage());
        }
    }

    /**
     * 재시도 횟수 소진 시 호출 — 최종 실패를 ERROR 레벨로 로깅
     */
    @Override
    public void onRetryPolicyExhaustion(RetryPolicy policy, Retryable<?> retryable, RetryException ex) {
        log.error("Retry exhausted after {} attempts — cause: {}",
                ex.getRetryCount(),
                ex.getCause() != null ? ex.getCause().getMessage() : ex.getMessage());
    }
}
