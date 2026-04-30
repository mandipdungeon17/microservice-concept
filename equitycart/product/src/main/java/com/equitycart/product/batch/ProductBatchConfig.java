package com.equitycart.product.batch;

import com.equitycart.product.dto.ProductCsvRow;
import com.equitycart.product.entity.Brand;
import com.equitycart.product.entity.Category;
import com.equitycart.product.entity.Product;
import com.equitycart.product.repository.BrandRepository;
import com.equitycart.product.repository.CategoryRepository;
import com.equitycart.product.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.batch.item.data.RepositoryItemWriter;
import org.springframework.batch.item.file.FlatFileItemReader;
import org.springframework.batch.item.file.builder.FlatFileItemReaderBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.FileSystemResource;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Spring Batch configuration for the product CSV import job. Defines a reader, processor, writer,
 * step, and job for bulk-importing products from a CSV file into the database.
 */
@Configuration
@RequiredArgsConstructor
public class ProductBatchConfig {

  private static final Logger log = LogManager.getLogger(ProductBatchConfig.class);

  private final BrandRepository brandRepository;
  private final CategoryRepository categoryRepository;
  private final ProductRepository productRepository;

  /**
   * Creates a step-scoped CSV reader that reads product rows from the uploaded file. Skips the
   * header row and maps comma-delimited columns to {@link ProductCsvRow} fields.
   *
   * @param filePath the path to the uploaded CSV file (injected from job parameters)
   * @return the configured flat file item reader
   */
  @Bean
  @StepScope
  public FlatFileItemReader<ProductCsvRow> productCsvReader(
      @Value("#{jobParameters['filePath']}") String filePath) {
    log.info("Initializing CSV reader for file: {}", filePath);
    return new FlatFileItemReaderBuilder<ProductCsvRow>()
        .name("productCsvReader")
        .resource(new FileSystemResource(filePath))
        .linesToSkip(1) // skip CSV header row
        .delimited() // comma-delimited
        .names(
            "name",
            "description",
            "sku",
            "price",
            "stockQuantity",
            "imageUrl",
            "brandId",
            "categoryId")
        .targetType(ProductCsvRow.class)
        .build();
  }

  /**
   * Creates an item processor that converts {@link ProductCsvRow} to {@link Product}. Looks up
   * brand and category by ID; skips the row if either is not found.
   *
   * @return the configured item processor
   */
  @Bean
  public ItemProcessor<ProductCsvRow, Product> productProcessor() {
    return csvRow -> {
      log.debug("Processing CSV row for SKU: {}", csvRow.getSku());
      Brand brand = brandRepository.findById(csvRow.getBrandId()).orElse(null);
      Category category = categoryRepository.findById(csvRow.getCategoryId()).orElse(null);

      if (brand == null || category == null) {
        log.warn(
            "Skipping row — invalid brandId {} or categoryId {}: {}",
            csvRow.getBrandId(),
            csvRow.getCategoryId(),
            csvRow.getSku());
        return null; // returning null SKIPS this item
      }

      log.debug("Successfully processed CSV row for SKU: {}", csvRow.getSku());
      return Product.builder()
          .name(csvRow.getName())
          .description(csvRow.getDescription())
          .sku(csvRow.getSku())
          .price(csvRow.getPrice())
          .stockQuantity(csvRow.getStockQuantity())
          .imageUrl(csvRow.getImageUrl())
          .brand(brand)
          .category(category)
          .build();
    };
  }

  /**
   * Creates a repository-based item writer that persists {@link Product} entities.
   *
   * @return the configured repository item writer
   */
  @Bean
  public RepositoryItemWriter<Product> productWriter() {
    RepositoryItemWriter<Product> writer = new RepositoryItemWriter<>();
    writer.setRepository(productRepository);
    writer.setMethodName("save");

    return writer;
  }

  /**
   * Defines the import step with a chunk size of 50, wiring reader, processor, and writer.
   *
   * @param jobRepository the Spring Batch job repository
   * @param transactionManager the platform transaction manager
   * @param reader the CSV reader
   * @param processor the row-to-entity processor
   * @param writer the entity writer
   * @return the configured step
   */
  @Bean
  public Step productImportStep(
      JobRepository jobRepository,
      PlatformTransactionManager transactionManager,
      FlatFileItemReader<ProductCsvRow> reader,
      ItemProcessor<ProductCsvRow, Product> processor,
      RepositoryItemWriter<Product> writer) {
    return new StepBuilder("productImportStep", jobRepository)
        .<ProductCsvRow, Product>chunk(50, transactionManager)
        .reader(reader)
        .processor(processor)
        .writer(writer)
        .build();
  }

  /**
   * Defines the product import job consisting of a single import step.
   *
   * @param jobRepository the Spring Batch job repository
   * @param productImportStep the import step to execute
   * @return the configured job
   */
  @Bean
  public Job productImportJob(JobRepository jobRepository, Step productImportStep) {
    log.info("Configuring productImportJob");
    return new JobBuilder("productImportJob", jobRepository).start(productImportStep).build();
  }
}
