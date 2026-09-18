package com.tx.api.test.generator.studio.logging;

import com.tx.api.test.generator.studio.dto.CollectionExport;
import com.tx.api.test.generator.studio.dto.CollectionImportResult;
import com.tx.api.test.generator.studio.dto.EnvironmentExport;
import com.tx.api.test.generator.studio.dto.ExcelExport;
import com.tx.api.test.generator.studio.model.StudioRequestFile;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.lang.reflect.Array;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;

@Aspect
@Component
public class MethodLoggingAspect {

    private static final Logger log = LoggerFactory.getLogger(MethodLoggingAspect.class);
    private static final int MAX_VALUE_LENGTH = 240;

    @Around("execution(public * com.tx.api.test.generator.studio.api..*(..)) || "
            + "execution(public * com.tx.api.test.generator.studio.service..*(..)) || "
            + "execution(public * com.tx.api.test.generator.studio.storage..*(..))")
    public Object logMethod(ProceedingJoinPoint joinPoint) throws Throwable {
        String call = describe(joinPoint);
        log.info("→ {}", call);
        long started = System.nanoTime();
        try {
            Object result = joinPoint.proceed();
            log.info("← {} [{}ms] result={}", call, elapsedMs(started), summarize(result));
            return result;
        } catch (Throwable thrown) {
            log.warn("✗ {} [{}ms] {}: {}", call, elapsedMs(started),
                    thrown.getClass().getSimpleName(), thrown.getMessage());
            throw thrown;
        }
    }

    private String describe(ProceedingJoinPoint joinPoint) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        String className = signature.getDeclaringType().getSimpleName();
        String methodName = signature.getName();
        String[] names = signature.getParameterNames();
        Object[] values = joinPoint.getArgs();
        StringBuilder arguments = new StringBuilder();
        for (int index = 0; index < values.length; index++) {
            if (index > 0) {
                arguments.append(", ");
            }
            String name = names != null && index < names.length ? names[index] : "arg" + index;
            arguments.append(name).append('=').append(summarize(values[index]));
        }
        return className + "." + methodName + "(" + arguments + ")";
    }

    private long elapsedMs(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }

    private String summarize(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Optional<?> optional) {
            return optional.map(this::summarize).orElse("Optional.empty");
        }
        if (value instanceof MultipartFile file) {
            return "MultipartFile[name=" + file.getOriginalFilename() + ", size=" + file.getSize() + "]";
        }
        if (value instanceof ExcelExport exported) {
            return "ExcelExport[fileName=" + exported.fileName()
                    + ", bytes=" + exported.content().length
                    + ", rows=" + exported.rowCount() + "]";
        }
        if (value instanceof CollectionExport exported) {
            return "CollectionExport[fileName=" + exported.fileName()
                    + ", bytes=" + exported.content().length
                    + ", cases=" + exported.caseCount()
                    + ", files=" + exported.fileCount() + "]";
        }
        if (value instanceof EnvironmentExport exported) {
            return "EnvironmentExport[fileName=" + exported.fileName()
                    + ", bytes=" + exported.content().length
                    + ", name=" + exported.name() + "]";
        }
        if (value instanceof CollectionImportResult imported) {
            return "CollectionImportResult[spec=" + imported.specName()
                    + ", plan=" + imported.planName()
                    + ", created=" + imported.createdCases()
                    + ", updated=" + imported.updatedCases()
                    + ", files=" + imported.importedFiles() + "]";
        }
        if (value instanceof StudioRequestFile file) {
            return "StudioRequestFile[id=" + file.getFileId()
                    + ", case=" + file.getTestCaseId()
                    + ", name=" + file.getFileName()
                    + ", bytes=" + file.getSizeBytes() + "]";
        }
        if (value instanceof com.tx.api.test.generator.studio.dto.RunAuth auth) {
            return auth.toString();
        }
        if (value instanceof byte[] bytes) {
            return "byte[" + bytes.length + "]";
        }
        if (value.getClass().isArray()) {
            return value.getClass().getComponentType().getSimpleName() + "[" + Array.getLength(value) + "]";
        }
        if (value instanceof Collection<?> collection) {
            return value.getClass().getSimpleName() + "[size=" + collection.size() + "]";
        }
        if (value instanceof Map<?, ?> map) {
            return value.getClass().getSimpleName() + "[size=" + map.size() + "]";
        }
        String text = String.valueOf(value).replaceAll("\\s+", " ");
        if (text.length() > MAX_VALUE_LENGTH) {
            return text.substring(0, MAX_VALUE_LENGTH) + "…";
        }
        return text;
    }
}
