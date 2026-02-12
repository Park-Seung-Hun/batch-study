package com.test.batchstudy.validator;

import org.springframework.batch.core.job.parameters.InvalidJobParametersException;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersValidator;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

import static com.test.batchstudy.constants.BatchConstants.PARAM_INPUT_FILE;
import static com.test.batchstudy.constants.BatchConstants.PARAM_RUN_DATE;

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
        String inputFile = parameters.getString(PARAM_INPUT_FILE);
        if (inputFile == null || inputFile.isBlank()) {
            throw new InvalidJobParametersException("inputFile is null or blank");
        }

        String runDate = parameters.getString(PARAM_RUN_DATE);
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
