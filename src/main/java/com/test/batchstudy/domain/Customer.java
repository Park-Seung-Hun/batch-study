package com.test.batchstudy.domain;

import java.time.LocalDateTime;

/**
 * 타깃 테이블(customer) 매핑용 DTO
 * <p>
 * CustomerStg에서 검증 통과한 데이터가 UPSERT되는 최종 테이블입니다.
 * customer_id가 UNIQUE 제약으로, 중복 시 UPDATE 처리됩니다.
 */
public record Customer(
        String customerId,
        String email,
        String name,
        String phone,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    /**
     * CustomerStg로부터 Customer 생성 (INSERT용)
     * createdAt, updatedAt은 DB에서 DEFAULT CURRENT_TIMESTAMP로 처리
     */
    public static Customer fromStaging(CustomerStg stg) {
        return new Customer(
                stg.customerId(),
                stg.email(),
                stg.name(),
                stg.phone(),
                null,  // DB DEFAULT
                null   // DB DEFAULT
        );
    }
}
