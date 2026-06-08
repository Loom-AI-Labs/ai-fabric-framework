package com.ai.infrastructure.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a field for AI-powered smart validation
 * 
 * This annotation indicates that a field should be validated
 * using AI capabilities for intelligent validation rules.
 * 
 * @author AI Infrastructure Team
 * @version 1.0.0
 */
@Target({ElementType.FIELD, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface AISmartValidation {
    
    /**
     * Validation rules to apply
     * 
     * @return validation rules
     */
    String[] rules() default {};
    
    /**
     * Whether to enable content validation
     * 
     * @return true if content validation is enabled
     */
    boolean validateContent() default true;
    
    /**
     * Whether to enable format validation
     * 
     * @return true if format validation is enabled
     */
    boolean validateFormat() default true;
    
    /**
     * Whether to enable semantic validation
     * 
     * @return true if semantic validation is enabled
     */
    boolean validateSemantic() default true;
    
    /**
     * Custom validation prompt
     * 
     * @return validation prompt
     */
    String prompt() default "";
    
    /**
     * Field name for validation
     * 
     * @return field name
     */
    String fieldName() default "";
    
    /**
     * Whether validation is required
     * 
     * @return true if validation is required
     */
    boolean required() default true;
    
    /**
     * Validation severity level
     * 
     * @return severity level
     */
    SeverityLevel severity() default SeverityLevel.ERROR;
    
    /**
     * Whether to enable real-time validation
     * 
     * @return true if real-time validation is enabled
     */
    boolean realTime() default false;
    
    /**
     * Custom validation context
     * 
     * @return validation context
     */
    String context() default "";
    
    /**
     * Whether to enable cross-field validation
     * 
     * @return true if cross-field validation is enabled
     */
    boolean crossField() default false;
    
    /**
     * Severity levels
     */
    enum SeverityLevel {
        INFO,
        WARNING,
        ERROR,
        CRITICAL
    }
}
