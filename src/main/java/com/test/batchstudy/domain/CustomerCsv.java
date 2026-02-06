package com.test.batchstudy.domain;

/**
 * CSV 파일 매핑용 DTO
 *
 * FlatFileItemReader가 CSV 행을 이 record로 변환합니다.
 * 필드명이 CSV 헤더와 일치해야 합니다.
 */
public record CustomerCsv(
        String customerId,
        String email,
        String name,
        String phone
) {
}
