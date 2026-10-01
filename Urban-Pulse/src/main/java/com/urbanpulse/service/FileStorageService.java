package com.urbanpulse.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

@Service
public class FileStorageService {

    private static final Logger logger = LoggerFactory.getLogger(FileStorageService.class);
    private final Path uploadDir = Paths.get("uploads").toAbsolutePath().normalize();

    public FileStorageService() {
        try {
            Files.createDirectories(uploadDir);
        } catch (IOException ex) {
            logger.error("Could not create upload directory", ex);
        }
    }

    public String storeFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Cannot store empty file.");
        }

        String originalFilename = file.getOriginalFilename();
        String extension = "";
        if (originalFilename != null && originalFilename.contains(".")) {
            extension = originalFilename.substring(originalFilename.lastIndexOf("."));
        } else {
            extension = ".jpg";
        }

        String newFilename = UUID.randomUUID().toString() + extension;

        try {
            Path targetLocation = this.uploadDir.resolve(newFilename);
            Files.copy(file.getInputStream(), targetLocation, StandardCopyOption.REPLACE_EXISTING);
            logger.info("Stored file: {}", newFilename);
            return "/uploads/" + newFilename;
        } catch (IOException ex) {
            logger.error("Failed to store file", ex);
            throw new RuntimeException("Could not store file. Please try again!", ex);
        }
    }
}
