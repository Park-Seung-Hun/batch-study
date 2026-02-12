package com.test.batchstudy.validator;

import org.springframework.batch.core.job.parameters.InvalidJobParametersException;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersValidator;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * Week 06: customerImportJob 파라미터 검증기
 * <p>
 * 검증 항목:
 * - inputFile: 필수, 파일 존재 여부
 * - runDate: 필수, yyyy-MM-dd 형식
 */
@Component
public class CustomerImportJobParametersValidator implements JobParametersValidator {

    @Override
    public void validate(JobParameters parameters) throws InvalidJobParametersException {
        String inputFile = parameters.getString("inputFile");
        if (inputFile == null || inputFile.isBlank()) {
            throw new InvalidJobParametersException("inputFile is null or blank");
        }

        String runDate = parameters.getString("runDate");
        if (runDate == null || runDate.isBlank()) {
            throw new InvalidJobParametersException("runDate is null or blank");
        }

        try {
            LocalDate.parse(runDate, DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        } catch (DateTimeParseException e) {
            throw new InvalidJobParametersException("runDate pattern is yyyy-mm-dd");
        }
    }
}
