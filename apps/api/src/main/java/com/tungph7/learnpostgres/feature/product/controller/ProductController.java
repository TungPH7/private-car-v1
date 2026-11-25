package com.tungph7.learnpostgres.feature.product.controller;

import java.net.URI;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import com.tungph7.learnpostgres.common.dto.PagedResponse;
import com.tungph7.learnpostgres.feature.product.domain.Product;
import com.tungph7.learnpostgres.feature.product.dto.CreateProductRequest;
import com.tungph7.learnpostgres.feature.product.dto.ProductResponse;
import com.tungph7.learnpostgres.feature.product.dto.UpdatePriceRequest;
import com.tungph7.learnpostgres.feature.product.dto.UpdateProductRequest;
import com.tungph7.learnpostgres.feature.product.mapper.ProductMapper;
import com.tungph7.learnpostgres.feature.product.service.ProductService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/products")
@Tag(name = "Products", description = "Catalog product management")
public class ProductController {

  private final ProductService service;
  private final ProductMapper mapper;

  public ProductController(ProductService service, ProductMapper mapper) {
    this.service = service;
    this.mapper = mapper;
  }

  /** Trả 201 Created + Location header. (Pattern REST chuẩn cho POST tạo.) */
  @PostMapping
  @ProductWrite
  @ResponseStatus(HttpStatus.CREATED)
  @Operation(
      summary = "Create a product",
      description = "Slug must be unique (case-insensitive). Returns 201 with Location header.")
  @ApiResponse(
      responseCode = "201",
      description = "Created",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = ProductResponse.class)))
  @ApiResponse(
      responseCode = "409",
      description = "Slug already exists",
      content =
          @Content(
              mediaType = "application/problem+json",
              schema = @Schema(ref = "#/components/schemas/ProblemDetail")))
  public ResponseEntity<ProductResponse> create(
      @Valid @RequestBody CreateProductRequest req, UriComponentsBuilder uriBuilder) {
    Product saved = service.create(req);
    URI location = uriBuilder.path("/api/products/{id}").buildAndExpand(saved.getId()).toUri();
    return ResponseEntity.created(location).body(mapper.toResponse(saved));
  }

  @GetMapping("/{id}")
  @Operation(summary = "Get product by id")
  @ApiResponse(responseCode = "200", description = "OK")
  @ApiResponse(
      responseCode = "404",
      description = "Product not found",
      content =
          @Content(
              mediaType = "application/problem+json",
              schema = @Schema(ref = "#/components/schemas/ProblemDetail")))
  public ProductResponse getById(
      @Parameter(description = "Product UUID", example = "0193c1f4-7b9a-7a3c-8d2e-aa1c98dead00")
          @PathVariable
          @NotNull
          UUID id) {
    return mapper.toResponse(service.getById(id)); // throw NotFoundException → handler 404
  }

  @GetMapping
  @Operation(
      summary = "List products, a page at a time",
      description =
          "page starts at 0; size at most 100; sort by name, price or createdAt (default"
              + " createdAt,desc); name keeps the products whose name contains it, ignoring case.")
  @ApiResponse(responseCode = "200", description = "OK")
  @ApiResponse(
      responseCode = "400",
      description = "Sort on a property that is not listed",
      content =
          @Content(
              mediaType = "application/problem+json",
              schema = @Schema(ref = "#/components/schemas/ProblemDetail")))
  public PagedResponse<ProductResponse> list(
      @Parameter(description = "Part of the name, ignoring case", example = "postgres")
          @RequestParam(required = false)
          String name,
      @ParameterObject
          @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC)
          Pageable pageable) {
    return PagedResponse.from(service.search(name, pageable).map(mapper::toResponse));
  }

  @PutMapping("/{id}")
  @ProductWrite
  @Operation(
      summary = "Replace the name, slug, price and description of a product",
      description = "Slug must stay unique (case-insensitive).")
  @ApiResponse(responseCode = "200", description = "OK")
  @ApiResponse(
      responseCode = "404",
      description = "Product not found",
      content =
          @Content(
              mediaType = "application/problem+json",
              schema = @Schema(ref = "#/components/schemas/ProblemDetail")))
  @ApiResponse(
      responseCode = "409",
      description = "Slug already used by another product",
      content =
          @Content(
              mediaType = "application/problem+json",
              schema = @Schema(ref = "#/components/schemas/ProblemDetail")))
  public ProductResponse update(
      @PathVariable UUID id, @Valid @RequestBody UpdateProductRequest req) {
    return mapper.toResponse(service.update(id, req));
  }

  @PutMapping("/{id}/price")
  @ProductWrite
  @Operation(summary = "Update product price")
  @ApiResponse(responseCode = "200", description = "OK")
  @ApiResponse(
      responseCode = "404",
      description = "Product not found",
      content =
          @Content(
              mediaType = "application/problem+json",
              schema = @Schema(ref = "#/components/schemas/ProblemDetail")))
  @ApiResponse(
      responseCode = "409",
      description = "Optimistic lock conflict",
      content =
          @Content(
              mediaType = "application/problem+json",
              schema = @Schema(ref = "#/components/schemas/ProblemDetail")))
  public ProductResponse updatePrice(
      @PathVariable UUID id, @Valid @RequestBody UpdatePriceRequest req) {
    return mapper.toResponse(
        service.updatePrice(id, req.price())); // optimistic lock → 409 (handler 03)
  }

  @PutMapping("/bulk-price/{slug}")
  @ProductWrite
  public int bulkUpdatePrice(
      @PathVariable String slug, @Valid @RequestBody UpdatePriceRequest req) {
    return service.bulkUpdatePriceBySlug(slug, req.price());
  }

  @DeleteMapping("/{id}")
  @ProductWrite
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @Operation(summary = "Delete a product")
  @ApiResponse(responseCode = "204", description = "Deleted")
  @ApiResponse(
      responseCode = "404",
      description = "Product not found",
      content =
          @Content(
              mediaType = "application/problem+json",
              schema = @Schema(ref = "#/components/schemas/ProblemDetail")))
  public void delete(@PathVariable UUID id) {
    service.delete(id); // throw NotFoundException → 404
  }
}
