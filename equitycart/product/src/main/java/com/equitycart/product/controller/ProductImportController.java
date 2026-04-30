package com.equitycart.product.controller;

import java.io.File;
import lombok.RequiredArgsConstructor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * REST controller for bulk product import via CSV file upload. Delegates to a Spring Batch job for
 * processing. Base path: {@code /api/products}
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/products")
public class ProductImportController {

  private static final Logger log = LogManager.getLogger(ProductImportController.class);

  private final JobLauncher jobLauncher;
  private final Job productImportJob;

  /**
   * Accepts a CSV file upload and launches the product import batch job. Restricted to ADMIN role.
   *
   * @param file the uploaded CSV file containing product data
   * @return a message indicating the job status
   * @throws Exception if file transfer or job launch fails
   */
  @PreAuthorize("hasRole('ADMIN')")
  @PostMapping("/import")
  public ResponseEntity<String> importProducts(@RequestParam("file") MultipartFile file)
      throws Exception {
    log.info(
        "POST /api/products/import - file: {}, size: {} bytes",
        file.getOriginalFilename(),
        file.getSize());

    // 1. Save uploaded file to temp location
    File tempFile = File.createTempFile("products-", ".csv");
    file.transferTo(tempFile);
    log.debug("Saved uploaded file to temp location: {}", tempFile.getAbsolutePath());

    // 2. Build job parameters with the file path
    JobParameters params =
        new JobParametersBuilder()
            .addString("filePath", tempFile.getAbsolutePath())
            .addLong("startTime", System.currentTimeMillis()) // makes each run unique
            .toJobParameters();

    // 3. Launch the batch job
    JobExecution execution = jobLauncher.run(productImportJob, params);
    log.info("Product import job launched with status: {}", execution.getStatus());

    // 4. Return status
    return ResponseEntity.ok("Job started with status: " + execution.getStatus());
  }
}
