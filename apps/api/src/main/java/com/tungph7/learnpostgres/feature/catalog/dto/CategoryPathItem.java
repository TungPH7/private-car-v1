package com.tungph7.learnpostgres.feature.catalog.dto;

import java.util.UUID;

public record CategoryPathItem(UUID id, String name, String slug) {}
