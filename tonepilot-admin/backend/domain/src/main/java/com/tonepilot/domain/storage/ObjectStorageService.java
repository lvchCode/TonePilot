package com.tonepilot.domain.storage;

import org.springframework.web.multipart.MultipartFile;

import java.util.Set;

public interface ObjectStorageService {

    StoredFile storeImage(MultipartFile file, String folder);

    StoredFile storeFile(MultipartFile file, String folder, Set<String> allowedExtensions, String emptyMessage, String unsupportedMessage);

    String writeTextFile(String folder, String fileName, String content);

    String writeBinaryFile(String folder, String fileName, byte[] bytes, String contentType);

    StoredObject readObject(String fileUrl);

    String readAsDataUrl(String fileUrl);

    String slug(String value);
}
