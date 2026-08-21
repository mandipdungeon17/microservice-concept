package com.equitycart.product.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.equitycart.commons.exception.DuplicateResourceException;
import com.equitycart.commons.exception.ResourceNotFoundException;
import com.equitycart.product.dto.BrandRequest;
import com.equitycart.product.dto.BrandResponse;
import com.equitycart.product.entity.Brand;
import com.equitycart.product.repository.BrandRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BrandServiceImplTest {

  @Mock private BrandRepository brandRepository;
  @InjectMocks private BrandServiceImpl brandService;

  @Test
  void createBrandShouldThrowWhenNameExists() {
    BrandRequest request = new BrandRequest("Nike", "d", "logo");
    when(brandRepository.existsByNameIgnoreCase("Nike")).thenReturn(true);

    assertThrows(DuplicateResourceException.class, () -> brandService.createBrand(request));
  }

  @Test
  void getAllBrandsShouldReturnMappedResponses() {
    Brand b = Brand.builder().name("Nike").description("d").logoUrl("l").build();
    b.setId(1L);
    when(brandRepository.findAll()).thenReturn(List.of(b));

    List<BrandResponse> responses = brandService.getAllBrands();

    assertEquals(1, responses.size());
    assertEquals("Nike", responses.getFirst().name());
  }

  @Test
  void createBrandShouldPersistAndReturnResponse() {
    BrandRequest request = new BrandRequest("Puma", "desc", "logo");
    Brand saved = Brand.builder().name("Puma").description("desc").logoUrl("logo").build();
    saved.setId(2L);

    when(brandRepository.existsByNameIgnoreCase("Puma")).thenReturn(false);
    when(brandRepository.save(org.mockito.ArgumentMatchers.any(Brand.class))).thenReturn(saved);

    BrandResponse response = brandService.createBrand(request);

    assertEquals(2L, response.id());
    assertEquals("Puma", response.name());
    verify(brandRepository).save(org.mockito.ArgumentMatchers.any(Brand.class));
  }

  @Test
  void getBrandByIdShouldReturnMappedBrand() {
    Brand brand = Brand.builder().name("Nike").description("d").logoUrl("l").build();
    brand.setId(1L);
    when(brandRepository.findById(1L)).thenReturn(Optional.of(brand));

    BrandResponse response = brandService.getBrandById(1L);

    assertEquals(1L, response.id());
    assertEquals("Nike", response.name());
  }

  @Test
  void getBrandByIdShouldThrowWhenNotFound() {
    when(brandRepository.findById(10L)).thenReturn(Optional.empty());

    assertThrows(ResourceNotFoundException.class, () -> brandService.getBrandById(10L));
  }
}
