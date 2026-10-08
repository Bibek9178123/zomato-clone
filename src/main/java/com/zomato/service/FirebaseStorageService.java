package com.zomato.service;

import com.google.cloud.storage.Blob;
import com.google.cloud.storage.Bucket;
import com.google.firebase.cloud.StorageClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
public class FirebaseStorageService {

    public String uploadImage(MultipartFile file, String folderName) throws IOException {
        Bucket bucket = StorageClient.getInstance().bucket();
        
        String fileName = folderName + "/" + UUID.randomUUID() + "-" + file.getOriginalFilename();
        Blob blob = bucket.create(fileName, file.getBytes(), file.getContentType());
        
        // Generate a public download URL (signed URL valid for a very long time, or we can construct public URL if bucket is public)
        // Here we construct a standard public Firebase Storage URL assuming the bucket has public read access for these paths
        String bucketName = bucket.getName();
        String publicUrl = String.format("https://firebasestorage.googleapis.com/v0/b/%s/o/%s?alt=media", 
                bucketName, fileName.replace("/", "%2F"));
        
        log.info("Successfully uploaded image to Firebase Storage: {}", publicUrl);
        return publicUrl;
    }
}
