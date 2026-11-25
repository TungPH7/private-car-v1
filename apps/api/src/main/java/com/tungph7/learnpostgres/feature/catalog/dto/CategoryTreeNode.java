package com.tungph7.learnpostgres.feature.catalog.dto;

import java.util.UUID;

public record CategoryTreeNode(
    UUID id, UUID parentId, String name, String slug, boolean active, int depth, String path) {}
