package com.tonepilot.starter.advice;

import com.tonepilot.application.agent.*;
import com.tonepilot.application.agent.workflow.*;
import com.tonepilot.application.agent.workflow.node.*;
import com.tonepilot.application.controller.*;
import com.tonepilot.application.controller.admin.*;
import com.tonepilot.application.dto.*;
import com.tonepilot.application.evaluation.*;
import com.tonepilot.application.knowledge.*;
import com.tonepilot.application.photo.*;
import com.tonepilot.application.runtime.*;
import com.tonepilot.application.style.*;
import com.tonepilot.domain.agent.*;
import com.tonepilot.domain.agent.workflow.*;
import com.tonepilot.domain.colorgrading.*;
import com.tonepilot.domain.common.*;
import com.tonepilot.domain.evaluation.*;
import com.tonepilot.domain.knowledge.*;
import com.tonepilot.domain.observability.*;
import com.tonepilot.domain.photo.*;
import com.tonepilot.domain.runtime.*;
import com.tonepilot.domain.storage.*;
import com.tonepilot.domain.style.*;
import com.tonepilot.repository.observability.*;
import com.tonepilot.repository.runtime.*;
import com.tonepilot.infrastructure.agent.*;
import com.tonepilot.infrastructure.ai.*;
import com.tonepilot.infrastructure.ai.dto.*;
import com.tonepilot.infrastructure.knowledge.douyin.*;
import com.tonepilot.infrastructure.knowledge.rag.*;
import com.tonepilot.infrastructure.knowledge.rag.config.*;
import com.tonepilot.infrastructure.observability.*;
import com.tonepilot.infrastructure.observability.config.*;
import com.tonepilot.infrastructure.observability.repository.*;
import com.tonepilot.infrastructure.runtime.repository.*;
import com.tonepilot.infrastructure.shared.persistence.*;
import com.tonepilot.infrastructure.storage.*;
import com.tonepilot.infrastructure.storage.config.*;
import com.tonepilot.starter.advice.*;
import com.tonepilot.starter.bootstrap.*;
import com.tonepilot.starter.config.*;
import com.tonepilot.starter.security.*;







import jakarta.validation.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotFound(NotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error(exception.getMessage()));
    }

    @ExceptionHandler({
            IllegalArgumentException.class,
            IllegalStateException.class,
            ConstraintViolationException.class
    })
    public ResponseEntity<ApiResponse<Void>> handleBadRequest(RuntimeException exception) {
        return ResponseEntity.badRequest().body(ApiResponse.error(exception.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException exception) {
        String message = exception.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return ResponseEntity.badRequest().body(ApiResponse.error(message));
    }
}
