package com.equitycart.product.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

import com.equitycart.commons.exception.DuplicateResourceException;
import com.equitycart.product.dto.BrandTickerMappingRequest;
import com.equitycart.product.dto.BrandTickerMappingResponse;
import com.equitycart.product.entity.Brand;
import com.equitycart.product.entity.BrandTickerMapping;
import com.equitycart.product.repository.BrandRepository;
import com.equitycart.product.repository.BrandTickerMappingRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BrandTickerMappingServiceImplTest {

  @Mock private BrandTickerMappingRepository brandTickerMappingRepository;
  @Mock private BrandRepository brandRepository;
  @InjectMocks private BrandTickerMappingServiceImpl service;

  @Test
  void createShouldThrowWhenDuplicateBrandTickerExists() {
    BrandTickerMappingRequest request = new BrandTickerMappingRequest(1L, "AAPL", "NASDAQ", new BigDecimal("2.0"));
    when(brandTickerMappingRepository.existsByBrandIdAndTickerSymbol(1L, "AAPL")).thenReturn(true);

    assertThrows(DuplicateResourceException.class, () -> service.createBrandTickerMapping(request));
  }

  @Test
  void getByBrandIdShouldMapResponses() {
    Brand brand = Brand.builder().name("Apple").build();
    brand.setId(11L);
    BrandTickerMapping mapping =
        BrandTickerMapping.builder()
            .brand(brand)
            .tickerSymbol("AAPL")
            .exchange("NASDAQ")
            .stockBackPercentage(new BigDecimal("2.0"))
            .build();
    mapping.setId(5L);
    when(brandTickerMappingRepository.findByBrandId(11L)).thenReturn(List.of(mapping));

    List<BrandTickerMappingResponse> responses = service.getByBrandId(11L);

    assertEquals(1, responses.size());
    assertEquals("AAPL", responses.getFirst().tickerSymbol());
  }

  @Test
  void createShouldPersistWhenValid() {
    BrandTickerMappingRequest request = new BrandTickerMappingRequest(1L, "AAPL", "NASDAQ", new BigDecimal("2.0"));
    Brand brand = Brand.builder().name("Apple").build();
    brand.setId(1L);
    BrandTickerMapping saved = BrandTickerMapping.builder().brand(brand).tickerSymbol("AAPL").exchange("NASDAQ").stockBackPercentage(new BigDecimal("2.0")).build();
    saved.setId(2L);

    when(brandTickerMappingRepository.existsByBrandIdAndTickerSymbol(1L, "AAPL")).thenReturn(false);
    when(brandRepository.findById(1L)).thenReturn(Optional.of(brand));
    when(brandTickerMappingRepository.save(org.mockito.ArgumentMatchers.any(BrandTickerMapping.class))).thenReturn(saved);

    BrandTickerMappingResponse response = service.createBrandTickerMapping(request);

    assertEquals(2L, response.id());
    assertEquals("AAPL", response.tickerSymbol());
  }
}

