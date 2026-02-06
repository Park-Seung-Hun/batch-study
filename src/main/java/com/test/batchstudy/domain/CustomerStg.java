package com.test.batchstudy.domain;

import java.time.LocalDate;

/**
 * 스테이징 테이블(customer_stg) 매핑용 DTO
 *
 * Processor에서 CustomerCsv → CustomerStg로 변환하며,
 * runDate를 추가하여 실행 기준일을 추적합니다.
 */
public record CustomerStg(
        String customerId,
        String email,
        String name,
        String phone,
        LocalDate runDate
) {
}
