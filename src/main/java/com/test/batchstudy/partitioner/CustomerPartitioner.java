package com.test.batchstudy.partitioner;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.partition.Partitioner;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

import static com.test.batchstudy.constants.ValidationSql.VALID_RECORD_FILTER;

/**
 * Week 07: customer_stg 테이블의 id 범위를 기반으로 파티션을 분할하는 Partitioner
 * <p>
 * 동작 방식:
 * 1. customer_stg에서 해당 runDate의 유효 레코드 MIN(id), MAX(id) 조회
 * 2. gridSize개의 파티션으로 id 범위를 균등 분할
 * 3. 각 파티션의 ExecutionContext에 minId, maxId 저장
 */
@Slf4j
@RequiredArgsConstructor
public class CustomerPartitioner implements Partitioner {

    private final JdbcTemplate jdbcTemplate;
    private final String runDate;

    @Override
    public Map<String, ExecutionContext> partition(int gridSize) {
        String query = "SELECT MIN(id), MAX(id) FROM customer_stg WHERE run_date = ? AND " + VALID_RECORD_FILTER;

        Map<String, ExecutionContext> result = new HashMap<>();

        jdbcTemplate.query(query, rs -> {
            long minId = rs.getLong(1);
            if (rs.wasNull()) {
                return; // 데이터 0건 → 빈 Map 반환
            }
            long maxId = rs.getLong(2);

            long targetSize = (maxId - minId) / gridSize + 1;
            long start = minId;

            for (int i = 0; i < gridSize && start <= maxId; i++) {
                long end = Math.min(start + targetSize - 1, maxId);

                ExecutionContext context = new ExecutionContext();
                context.putLong("minId", start);
                context.putLong("maxId", end);
                result.put("partition" + i, context);

                log.info("Partition {} — id range [{}, {}]", i, start, end);
                start = end + 1;
            }
        }, LocalDate.parse(runDate));

        log.info("Created {} partitions for runDate={}", result.size(), runDate);
        return result;
    }
}
