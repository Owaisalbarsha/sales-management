package com.salesmanagement.invoicing.internal.dto;

import org.springframework.core.io.Resource;

/**
 * An ePOD file resolved for download: the bytes plus what the client needs to render them.
 *
 * @param resource    the file content
 * @param contentType detected MIME type (e.g. image/png)
 * @param filename    the stored filename, used for the Content-Disposition header
 */
public record EpodFile(
        Resource resource,
        String   contentType,
        String   filename
) {}