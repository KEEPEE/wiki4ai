package com.wiki4ai.exception;

/**
 * Thrown when a content edit (find/replace) cannot be applied unambiguously:
 * zero occurrences of 'find', or more than one occurrence while replaceAll is
 * not set. Extends BadRequestException (shared with WIKI4AI-23) and is mapped
 * to 400 Bad Request by GlobalExceptionHandler with editIndex and occurrences
 * details in the body.
 */
public class ContentEditException extends BadRequestException {

    private final Integer editIndex;
    private final Integer occurrences;

    public ContentEditException(String message, Integer editIndex, Integer occurrences) {
        super(message);
        this.editIndex = editIndex;
        this.occurrences = occurrences;
    }

    public Integer getEditIndex() {
        return editIndex;
    }

    public Integer getOccurrences() {
        return occurrences;
    }
}
