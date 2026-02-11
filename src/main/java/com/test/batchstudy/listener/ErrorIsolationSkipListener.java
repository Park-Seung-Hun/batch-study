package com.test.batchstudy.listener;

import com.test.batchstudy.domain.CustomerCsv;
import com.test.batchstudy.domain.CustomerStg;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.listener.SkipListener;
import org.springframework.batch.core.scope.context.StepSynchronizationManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Date;

/**
 * Week 05: Skip된 레코드를 customer_err 테이블에 격리하는 Listener
 * <p>
 * SkipListener 콜백 시점에 StepSynchronizationManager를 통해
 * 현재 실행 중인 Step의 JobParameter에서 runDate를 가져옵니다.
 * 이 방식은 @StepScope 프록시 없이도 JobParameter에 접근 가능합니다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ErrorIsolationSkipListener implements SkipListener<CustomerCsv, CustomerStg> {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public void onSkipInRead(Throwable t) {
        String errMsg = getErrMsg(t);
        log.warn("Skip in Read: {}", errMsg);
        insertErr(errMsg);
    }

    @Override
    public void onSkipInProcess(CustomerCsv item, Throwable t) {
        String errMsg = getErrMsg(t);
        log.warn("Skip in Process - customerId={}: {}", item.customerId(), errMsg);
        insertErr(errMsg, item.customerId(), item.email(), item.name(), item.phone());
    }

    @Override
    public void onSkipInWrite(CustomerStg item, Throwable t) {
        String errMsg = getErrMsg(t);
        log.warn("Skip in Write - customerId={}: {}", item.customerId(), errMsg);
        insertErr(errMsg, item.customerId(), item.email(), item.name(), item.phone());
    }

    private void insertErr(String errMsg) {
        String sql = "INSERT INTO customer_err (customer_id, email, name, phone, error_message, run_date) " +
                "VALUES(null, null, null, null, ?, ?)";
        jdbcTemplate.update(sql, errMsg, Date.valueOf(getRunDate()));
    }

    private void insertErr(String errMsg, String customerId, String email, String name, String phone) {
        String sql = "INSERT INTO customer_err (customer_id, email, name, phone, error_message, run_date) " +
                "VALUES(?, ?, ?, ?, ?, ?)";
        jdbcTemplate.update(sql, customerId, email, name, phone, errMsg, Date.valueOf(getRunDate()));
    }

    private String getRunDate() {
        return StepSynchronizationManager.getContext()
                .getStepExecution()
                .getJobParameters()
                .getString("runDate");
    }

    private String getErrMsg(Throwable t) {
        return t.getMessage();
    }
}
