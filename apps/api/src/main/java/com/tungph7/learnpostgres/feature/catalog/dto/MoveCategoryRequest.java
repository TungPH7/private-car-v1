package com.tungph7.learnpostgres.feature.catalog.dto;

import java.util.UUID;

/** newParentId == null → move thành root. newParentId != null → move thành con của parent đó. */
public record MoveCategoryRequest(UUID newParentId) {}
